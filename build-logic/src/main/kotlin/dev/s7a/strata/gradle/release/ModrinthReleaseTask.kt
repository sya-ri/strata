package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files

/**
 * Executes one explicit, non-cacheable Modrinth reconciliation phase.
 *
 * The task reads its token only at execution, never declares it as an input or output, and persists only a redacted receipt.
 * Its receipt is owned outside the cacheable manifest bundle so the two tasks never overlap outputs.
 * Every invocation observes live external state and therefore deliberately bypasses Gradle up-to-date reuse.
 */
@DisableCachingByDefault(because = "The task reconciles mutable external Modrinth state.")
internal abstract class ModrinthReleaseTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val apiBaseUrl: Property<String>

    @get:Input
    abstract val operation: Property<Operation>

    @get:Internal
    abstract val token: Property<String>

    @get:OutputFile
    abstract val receiptFile: RegularFileProperty

    /**
     * Reconciles the selected phase against live Modrinth state and replaces this task's credential-free receipt.
     *
     * The action is thread-confined to the Gradle worker, reads the PAT only at execution, sends it only to the validated official endpoint, and fails closed without a receipt when authentication, metadata, hashes, or remote lifecycle state differ.
     */
    @TaskAction
    fun reconcile() {
        val output = receiptFile.get().asFile
        Files.deleteIfExists(output.toPath())
        val secret =
            token.orNull?.takeIf(String::isNotBlank)
                ?: error("MODRINTH_TOKEN is required for $name; no remote mutation was attempted.")
        val manifestPath = manifestFile.get().asFile
        val manifest = ModrinthManifest.read(manifestPath)
        val authenticatedApiBaseUrl = validateAuthenticatedApiBaseUrl(apiBaseUrl.get())
        val api =
            ModrinthApiClient(
                apiBaseUrl = authenticatedApiBaseUrl,
                token = secret,
                userAgent = "sya-ri/strata/${manifest.releaseVersion} (https://gh.s7a.dev/strata/)",
            )
        val coordinator = ModrinthReleaseCoordinator(manifest, manifestPath.parentFile, api)
        val receipt =
            when (operation.get()) {
                Operation.PREFLIGHT -> coordinator.preflight()
                Operation.STAGE -> coordinator.stage()
                Operation.SUBMIT -> coordinator.submit()
                Operation.FINALIZE_PROJECT -> coordinator.finalizeProject()
                Operation.VERIFY -> coordinator.verify()
            }
        output.parentFile.mkdirs()
        output.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    linkedMapOf(
                        "operation" to receipt.operation,
                        "projectId" to receipt.projectId,
                        "projectStatus" to receipt.projectStatus,
                        "absent" to receipt.absent,
                        "listed" to receipt.listed,
                    ),
                ),
            ) + "\n",
        )
    }

    /**
     * Supported monotonic reconciliation phases.
     */
    internal enum class Operation {
        /**
         * Read-only release preflight.
         */
        PREFLIGHT,

        /**
         * Idempotent missing-version staging.
         */
        STAGE,

        /**
         * Monotonic project submission.
         */
        SUBMIT,

        /**
         * Approved-project body transition after predecessor verification.
         */
        FINALIZE_PROJECT,

        /**
         * Read-only approved-release verification.
         */
        VERIFY,
    }

    /**
     * Owns authenticated endpoint validation shared with functional tests.
     */
    companion object {
        private val AUTHENTICATED_API_BASE_URI = URI("https://api.modrinth.com/v2")

        /**
         * Validates the only authenticated Modrinth endpoint to which a release PAT may be sent.
         */
        internal fun validateAuthenticatedApiBaseUrl(value: String): String {
            val normalized = value.removeSuffix("/")
            val uri = URI(normalized)
            check(
                uri == AUTHENTICATED_API_BASE_URI &&
                    normalized == AUTHENTICATED_API_BASE_URI.toASCIIString(),
            ) {
                "Authenticated Modrinth tasks require the exact endpoint https://api.modrinth.com/v2."
            }
            return normalized
        }
    }
}
