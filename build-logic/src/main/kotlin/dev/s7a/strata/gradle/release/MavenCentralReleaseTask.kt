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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject

/**
 * Produces live Maven Central absence or exact-match evidence without remote mutation.
 *
 * The task reads only the tracked coordinate matrix, local Maven repository, and public Central endpoints.
 * It deletes its exact prior receipt before every attempt so a failed rerun cannot preserve stale success evidence.
 */
@DisableCachingByDefault(because = "The task verifies live immutable Maven Central state.")
internal abstract class MavenCentralReleaseTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coordinatesFile: RegularFileProperty

    @get:Internal
    abstract val localRepository: DirectoryProperty

    @get:Input
    abstract val repositoryBaseUrl: Property<String>

    @get:Input
    abstract val operation: Property<Operation>

    @get:OutputFile
    abstract val receiptFile: RegularFileProperty

    @get:Optional
    @get:OutputDirectory
    abstract val canonicalSignatureDirectory: DirectoryProperty

    @get:Optional
    @get:OutputDirectory
    abstract val canonicalEvidenceDirectory: DirectoryProperty

    /** Reads live Central state and replaces the task-owned receipt only after successful verification. */
    @TaskAction
    fun verifyRelease() {
        val output = receiptFile.get().asFile
        Files.deleteIfExists(output.toPath())
        val signatureDirectory = canonicalSignatureDirectory.orNull?.asFile
        if (signatureDirectory != null) fileSystemOperations.delete { delete(signatureDirectory) }
        val evidenceDirectory = canonicalEvidenceDirectory.orNull?.asFile
        if (evidenceDirectory != null) fileSystemOperations.delete { delete(evidenceDirectory) }
        val repositoryUrl = validateRepositoryBaseUrl(repositoryBaseUrl.get())
        val coordinates = coordinatesFile.get().asFile.readLines()
        val verifier =
            MavenCentralReleaseVerifier(
                localRepository = localRepository.get().asFile.toPath(),
                repositoryBaseUri = URI(repositoryUrl),
            )
        val receipt =
            when (operation.get()) {
                Operation.PREFLIGHT -> verifier.preflight(coordinates)
                Operation.VERIFY -> {
                    val verified = verifier.verify(coordinates)
                    val directory = canonicalSignatureDirectory.orNull?.asFile
                        ?: error("Maven Central final verification requires a canonical signature output directory.")
                    val evidence = canonicalEvidenceDirectory.orNull?.asFile
                        ?: error("Maven Central final verification requires a canonical evidence output directory.")
                    val signedFiles = verifier.stageCanonicalPublicationEvidence(coordinates, evidence.toPath())
                    directory.mkdirs()
                    signedFiles.filter(MavenCentralReleaseVerifier.SignedPublicationFile::githubDistributionSignature).forEach { file ->
                        val source = evidence.toPath().resolve(file.signatureRelativePath)
                        Files.copy(source, directory.toPath().resolve(Path.of(file.signatureRelativePath).fileName))
                    }
                    verified
                }
            }
        output.parentFile.mkdirs()
        output.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    linkedMapOf(
                        "operation" to operation.get().wireValue,
                        "state" to receipt.state.wireValue,
                        "coordinateCount" to receipt.coordinateCount,
                        "verifiedFileCount" to receipt.verifiedFileCount,
                        "verifiedChecksumCount" to receipt.verifiedChecksumCount,
                    ),
                ),
            ) + "\n",
        )
    }

    /** Monotonic Maven Central read phases selected by the protected release workflow. */
    internal enum class Operation(
        val wireValue: String,
    ) {
        PREFLIGHT("preflight"),
        VERIFY("verify"),
    }

    companion object {
        private const val OFFICIAL_REPOSITORY_BASE_URL = "https://repo1.maven.org/maven2/"

        /** Validates the fixed public repository endpoint used for immutable release evidence. */
        internal fun validateRepositoryBaseUrl(value: String): String {
            val normalized = if (value.endsWith('/')) value else "$value/"
            check(normalized == OFFICIAL_REPOSITORY_BASE_URL) {
                "Maven Central release tasks require the exact endpoint $OFFICIAL_REPOSITORY_BASE_URL"
            }
            return normalized
        }
    }
}
