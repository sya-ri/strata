package dev.s7a.strata.gradle.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Generates the exact signed GitHub Release asset directory from the canonical Modrinth artifact bundle. */
@CacheableTask
internal abstract class GenerateGithubReleaseBundle @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signatureDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Replaces the task-owned output with the exact canonical JARs, detached signatures, and deterministic checksum inventory.
     *
     * The action reads only [manifestFile], [artifactDirectory], and [signatureDirectory], writes only [outputDirectory], and fails when any artifact, signature, or expected asset count differs from the manifest contract.
     */
    @TaskAction
    fun generate() {
        val manifest = ModrinthManifest.read(manifestFile.get().asFile)
        val root = outputDirectory.get().asFile
        fileSystemOperations.delete { delete(root) }
        root.mkdirs()
        val artifactRoot = artifactDirectory.get().asFile
        val artifactFiles = artifactRoot.listFiles().orEmpty().filter(File::isFile)
        check(artifactFiles.map(File::getName).toSet() == manifest.artifacts.map(ModrinthManifest.Artifact::fileName).toSet()) {
            "Canonical Modrinth artifact directory must contain exactly the 20 manifest JARs."
        }
        val signatureRoot = signatureDirectory.get().asFile
        val signatures = signatureRoot.listFiles().orEmpty().filter(File::isFile)
        check(signatures.size == ModrinthManifest.EXPECTED_ARTIFACT_COUNT) {
            "GitHub release generation requires exactly 20 detached signatures."
        }
        val releaseFiles = mutableListOf<File>()
        manifest.artifacts.forEach { artifact ->
            val source = artifactRoot.resolve(artifact.fileName)
            check(source.isFile) { "Canonical Modrinth artifact is missing: ${artifact.fileName}" }
            val sourceHashes = source.hashes()
            check(source.length() == artifact.size) { "Canonical Modrinth artifact ${artifact.fileName} differs from manifest size." }
            check(sourceHashes.first == artifact.sha256) {
                "Canonical Modrinth artifact ${artifact.fileName} differs from manifest SHA-256."
            }
            check(sourceHashes.second == artifact.sha512) {
                "Canonical Modrinth artifact ${artifact.fileName} differs from manifest SHA-512."
            }
            val artifactOutput = root.resolve(artifact.githubAssetName)
            source.copyTo(artifactOutput, overwrite = false)
            val signatureSource = signatureRoot.resolve("${artifact.fileName}.asc")
            check(signatureSource.isFile && 0L < signatureSource.length()) {
                "Detached signature is missing or empty for ${artifact.fileName}."
            }
            val signatureOutput = root.resolve("${artifact.githubAssetName}.asc")
            signatureSource.copyTo(signatureOutput, overwrite = false)
            releaseFiles += artifactOutput
            releaseFiles += signatureOutput
        }
        val checksumFile = root.resolve("SHA256SUMS")
        checksumFile.writeText(
            releaseFiles
                .sortedBy(File::getName)
                .joinToString(separator = "\n", postfix = "\n") { file -> "${file.sha256()}  ${file.name}" },
        )
        check(root.listFiles().orEmpty().size == EXPECTED_GITHUB_ASSET_COUNT) {
            "GitHub release bundle must contain exactly $EXPECTED_GITHUB_ASSET_COUNT assets."
        }
    }

    private fun File.sha256(): String {
        return hash("SHA-256")
    }

    private fun File.hashes(): Pair<String, String> = hash("SHA-256") to hash("SHA-512")

    private fun File.hash(algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val EXPECTED_GITHUB_ASSET_COUNT = 41
    }
}
