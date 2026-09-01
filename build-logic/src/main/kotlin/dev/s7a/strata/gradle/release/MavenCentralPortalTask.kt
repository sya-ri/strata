package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import javax.inject.Inject

/**
 * Produces authenticated, read-only Central Publisher Portal deployment evidence.
 *
 * The task resolves the canonical artifact inventory against [releaseVersion] and owns and replaces only its receipt and evidence directory.
 * Credentials are internal inputs, are passed directly to the short-lived coordinator, and never appear in outputs.
 */
@DisableCachingByDefault(because = "The task verifies live authenticated Central Publisher Portal state.")
internal abstract class MavenCentralPortalTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val coordinatesFile: RegularFileProperty

        @get:Input
        abstract val releaseVersion: Property<String>

        @get:Internal
        abstract val localRepository: DirectoryProperty

        @get:Input
        abstract val portalBaseUrl: Property<String>

        @get:Internal
        abstract val username: Property<String>

        @get:Internal
        abstract val password: Property<String>

        @get:Input
        abstract val operation: Property<Operation>

        @get:OutputFile
        abstract val receiptFile: RegularFileProperty

        @get:OutputDirectory
        abstract val evidenceDirectory: DirectoryProperty

        /**
         * Reads live Portal state, stages the exact signed deployment content, and replaces the receipt only on success.
         *
         * The action is thread-confined to its Gradle worker, reads credentials only at execution, sends them only to the pinned official Portal origin, and writes only [receiptFile] and [evidenceDirectory].
         * Missing credentials, response-shape drift, duplicate or incomplete deployments, byte or checksum differences, and non-terminal lifecycle exhaustion fail without a receipt.
         */
        @TaskAction
        fun reconcile() {
            val receipt = receiptFile.get().asFile
            Files.deleteIfExists(receipt.toPath())
            val evidence = evidenceDirectory.get().asFile
            fileSystemOperations.delete { delete(evidence) }
            val usernameValue =
                username.orNull?.takeIf(String::isNotBlank)
                    ?: error("Maven Central Portal username is required; no authenticated request was attempted.")
            val passwordValue =
                password.orNull?.takeIf(String::isNotBlank)
                    ?: error("Maven Central Portal password is required; no authenticated request was attempted.")
            val coordinator =
                MavenCentralPortalCoordinator(
                    portalBaseUri = URI(validatePortalBaseUrl(portalBaseUrl.get())),
                    username = usernameValue,
                    password = passwordValue,
                    localRepository = localRepository.get().asFile.toPath(),
                )
            val coordinates =
                MavenReleaseCoordinates.resolve(
                    coordinatesFile.get().asFile.readLines(),
                    releaseVersion.get(),
                )
            val result =
                when (operation.get()) {
                    Operation.PREFLIGHT -> coordinator.preflight(coordinates, evidence.toPath())
                    Operation.VERIFY -> coordinator.verifyUntilPublished(coordinates, evidence.toPath())
                }
            receipt.parentFile.mkdirs()
            receipt.writeText(
                JsonOutput.prettyPrint(
                    JsonOutput.toJson(
                        linkedMapOf(
                            "operation" to operation.get().wireValue,
                            "state" to result.state.wireValue,
                            "deploymentId" to result.deploymentId,
                            "deploymentState" to result.deploymentState?.wireValue,
                            "verifiedContentFileCount" to result.verifiedContentFileCount,
                            "verifiedChecksumCount" to result.verifiedChecksumCount,
                        ),
                    ),
                ) + "\n",
            )
        }

        /**
         * Authenticated Portal phases used by the protected release workflow.
         */
        internal enum class Operation(
            val wireValue: String,
        ) {
            /**
             * Verifies that no conflicting deployment exists before publication.
             */
            PREFLIGHT("preflight"),

            /**
             * Verifies the exact deployment through its terminal published state.
             */
            VERIFY("verify"),
        }

        /**
         * Owns validation for the authenticated Central Publisher Portal origin.
         */
        companion object {
            private const val OFFICIAL_PORTAL_BASE_URL = "https://central.sonatype.com/"

            /**
             * Validates the fixed official Portal origin used for authenticated release reconciliation.
             */
            internal fun validatePortalBaseUrl(value: String): String {
                val normalized = if (value.endsWith('/')) value else "$value/"
                check(normalized == OFFICIAL_PORTAL_BASE_URL) {
                    "Maven Central Portal tasks require the exact endpoint $OFFICIAL_PORTAL_BASE_URL"
                }
                return normalized
            }
        }
    }
