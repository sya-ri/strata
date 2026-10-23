package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.PROJECT_ID
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Builds remote version response records independently of the production client parser.
 */
internal class MockModrinthVersionResponses(
    private val fixture: ModrinthReleaseFixture,
    private val baseUrlProvider: () -> String,
) {
    private val baseUrl get() = baseUrlProvider()
    var corruptFirstVersionName: Boolean = false
    var dependencyResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
    var fileResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
    var fileHashesResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
    val uploadedFiles: MutableMap<String, ByteArray> = linkedMapOf()

    /**
     * Builds one listed release, including deliberate response-schema mutations.
     */
    fun version(artifact: ModrinthManifest.Artifact): Map<String, Any?> {
        val bytes = uploadedFiles[artifact.fileName] ?: fixture.bundle.resolve(artifact.relativePath).readBytes()
        val dependencies =
            ModrinthManifest.REQUIRED_PROJECT_DEPENDENCIES.map { projectId ->
                linkedMapOf<String, Any?>(
                    "project_id" to projectId,
                    "version_id" to null,
                    "file_name" to null,
                    "dependency_type" to "required",
                ).also {
                    dependencyResponseMutation(it)
                }
            }
        val hashes =
            linkedMapOf<String, Any?>(
                "sha256" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "sha512" to HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)),
            )
        fileHashesResponseMutation(hashes)
        val file =
            linkedMapOf<String, Any?>(
                "hashes" to hashes,
                "url" to "$baseUrl/cdn/${artifact.fileName}",
                "filename" to artifact.fileName,
                "primary" to true,
                "size" to bytes.size.toLong(),
            )
        fileResponseMutation(file)
        return linkedMapOf(
            "id" to "id-${artifact.gameVersion}",
            "project_id" to PROJECT_ID,
            "name" to
                if (corruptFirstVersionName && artifact == fixture.manifest.artifacts.first()) {
                    "corrupt"
                } else {
                    artifact.versionName
                },
            "version_number" to artifact.versionNumber,
            "changelog" to fixture.manifest.changelog,
            "dependencies" to dependencies,
            "game_versions" to listOf(artifact.gameVersion),
            "version_type" to "release",
            "loaders" to listOf("fabric"),
            "featured" to true,
            "status" to "listed",
            "environment" to "client_only_server_optional",
            "files" to listOf(file),
        )
    }

    /**
     * Builds a known predecessor whose artifact bytes differ from this release.
     */
    fun historicalVersion(artifact: ModrinthManifest.Artifact): Map<String, Any?> =
        version(artifact) +
            mapOf(
                "id" to "historical-${artifact.gameVersion}",
                "name" to "Strata 0.1.0 for Minecraft ${artifact.gameVersion}",
                "version_number" to "0.1.0+mc${artifact.gameVersion}",
                "changelog" to "Initial release\n",
                "files" to
                    listOf(
                        mapOf(
                            "hashes" to mapOf("sha256" to "e".repeat(64), "sha512" to "f".repeat(128)),
                            "url" to "$baseUrl/cdn/historical-${artifact.gameVersion}.jar",
                            "filename" to "strata-runtime-minecraft-fabric-${artifact.gameVersion}-0.1.0.jar",
                            "primary" to true,
                            "size" to 1,
                        ),
                    ),
            )

    /**
     * Builds an unrelated release that must prevent bootstrap writes.
     */
    fun unexpectedVersion(): Map<String, Any?> {
        val artifact = fixture.manifest.artifacts.first()
        return version(artifact) +
            mapOf(
                "id" to "unexpected-id",
                "version_number" to "unexpected-version",
                "files" to
                    listOf(
                        mapOf(
                            "hashes" to mapOf("sha256" to "0".repeat(64), "sha512" to "0".repeat(128)),
                            "url" to "$baseUrl/cdn/unexpected.jar",
                            "filename" to "unexpected.jar",
                            "primary" to true,
                            "size" to 1,
                        ),
                    ),
            )
    }
}
