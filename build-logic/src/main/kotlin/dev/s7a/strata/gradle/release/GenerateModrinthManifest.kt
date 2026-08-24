package dev.s7a.strata.gradle.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Generates the canonical, credential-free Modrinth and GitHub release bundle.
 */
@CacheableTask
internal abstract class GenerateModrinthManifest
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:Input
        abstract val projectId: Property<String>

        @get:Input
        abstract val releaseVersion: Property<String>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val releaseNotesFile: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val projectMetadataFile: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val projectBodyFile: RegularFileProperty

        @get:Input
        abstract val gameVersions: ListProperty<String>

        @get:Input
        abstract val canonicalFileNames: ListProperty<String>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val artifactFiles: ListProperty<RegularFile>

        @get:Input
        abstract val orderedArtifactSha256: ListProperty<String>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val projectAssetFiles: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        /**
         * Replaces the task-owned bundle with one manifest and the exact canonical artifacts declared by the ordered target inputs.
         *
         * The action reads only tracked release metadata and verified artifacts, writes only [outputDirectory], and fails before emitting a manifest when target ordering, hashes, assets, or release metadata differ from the configured contract.
         * Validation and emission remain one transaction so no partial manifest can escape.
         */
        @Suppress("LongMethod")
        @TaskAction
        fun generate() {
            val versions = gameVersions.get()
            val names = canonicalFileNames.get()
            val files = artifactFiles.get().map(RegularFile::getAsFile)
            val expectedSha256 = orderedArtifactSha256.get()
            check(versions.size == names.size && names.size == files.size && files.size == expectedSha256.size) {
                "Release target metadata, artifact providers, and ordered hashes must have identical sizes."
            }
            check(releaseVersion.get().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"))) {
                "The release version must be a stable semantic version."
            }

            val root = outputDirectory.get().asFile
            fileSystemOperations.delete { delete(root) }
            val artifactsDirectory = root.resolve("artifacts")
            artifactsDirectory.mkdirs()
            val artifacts =
                versions.indices.map { index ->
                    val source = files[index]
                    check(source.isFile) { "Verified release artifact does not exist: $source" }
                    check(sha256(source) == expectedSha256[index]) {
                        "Verified release artifact changed after its ordered cache identity was captured: $source"
                    }
                    val canonicalName = names[index]
                    check(canonicalName.matches(Regex("[0-9A-Za-z.+_-]+\\.jar"))) {
                        "Unsafe canonical release filename: $canonicalName"
                    }
                    val destination = artifactsDirectory.resolve(canonicalName)
                    source.copyTo(destination, overwrite = false)
                    val hashes = destination.hashes()
                    check(hashes.first == expectedSha256[index]) {
                        "Copied release artifact differs from its ordered cache identity: $canonicalName"
                    }
                    ModrinthManifest.Artifact(
                        gameVersion = versions[index],
                        versionNumber = "${releaseVersion.get()}+mc${versions[index]}",
                        versionName = "Strata ${releaseVersion.get()} for Minecraft ${versions[index]}",
                        fileName = canonicalName,
                        relativePath = "artifacts/$canonicalName",
                        size = destination.length(),
                        sha256 = hashes.first,
                        sha512 = hashes.second,
                        mavenCoordinate =
                            "dev.s7a.strata:strata-runtime-minecraft-fabric-${versions[index]}:${releaseVersion.get()}",
                        githubAssetName = canonicalName,
                    )
                }
            val changelog =
                releaseNotesFile
                    .get()
                    .asFile
                    .readText()
                    .replace("\r\n", "\n")
                    .trimEnd() + "\n"
            check(changelog.isNotBlank()) { "Release notes must not be blank." }
            val projectBody =
                projectBodyFile
                    .get()
                    .asFile
                    .readText()
                    .replace("\r\n", "\n")
                    .trimEnd() + "\n"
            val projectMetadataPath = projectMetadataFile.get().asFile
            val projectMetadata = ModrinthManifest.readProjectMetadata(projectMetadataPath, projectBody)
            val trackedProjectId = ModrinthManifest.readTrackedProjectId(projectMetadataPath).trim()
            val configuredProjectId = projectId.get().trim()
            check(trackedProjectId.isBlank() || configuredProjectId.isBlank() || trackedProjectId == configuredProjectId) {
                "Configured Modrinth project ID differs from the immutable tracked project ID."
            }
            val resolvedProjectId = trackedProjectId.ifBlank { configuredProjectId }
            verifyProjectAssets(projectMetadata)
            ModrinthManifest(
                schemaVersion = ModrinthManifest.CURRENT_SCHEMA_VERSION,
                projectId = resolvedProjectId,
                project = projectMetadata,
                releaseVersion = releaseVersion.get(),
                changelog = changelog,
                artifacts = artifacts,
            ).also(ModrinthManifest::validate).write(root.resolve("manifest.json"))
        }

        private fun File.hashes(): Pair<String, String> {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val sha512 = MessageDigest.getInstance("SHA-512")
            inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha256.update(buffer, 0, count)
                    sha512.update(buffer, 0, count)
                }
            }
            return sha256.hex() to sha512.hex()
        }

        private fun verifyProjectAssets(metadata: ModrinthManifest.ProjectMetadata) {
            val expected = listOf(metadata.icon) + metadata.gallery.map { asset -> ModrinthManifest.ProjectAsset(asset.path, asset.sha256) }
            val supplied = projectAssetFiles.files.associateBy { file -> file.relativeTo(project.rootDir).invariantSeparatorsPath }
            check(supplied.keys == expected.map(ModrinthManifest.ProjectAsset::path).toSet()) {
                "Release project-asset inputs must match every tracked icon and gallery path exactly."
            }
            expected.forEach { asset ->
                val file = supplied.getValue(asset.path)
                check(file.isFile) { "Tracked Modrinth project asset is missing: ${asset.path}" }
                check(file.hashes().first == asset.sha256) {
                    "Tracked SHA-256 differs for Modrinth project asset ${asset.path}."
                }
            }
        }

        /**
         * Owns deterministic artifact hashing used by release task inputs.
         */
        companion object {
            /**
             * Computes the artifact content hash used as one lazily realized, order-sensitive Gradle input.
             */
            internal fun sha256(file: File): String {
                check(file.isFile) { "Verified release artifact does not exist: $file" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.hex()
            }

            private fun MessageDigest.hex(): String = digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
