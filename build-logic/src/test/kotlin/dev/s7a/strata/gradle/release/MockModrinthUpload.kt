package dev.s7a.strata.gradle.release

import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets

/**
 * Validates the actual two-part HTTP upload independently of the release client's multipart publisher.
 */
internal class MockModrinthUpload private constructor(
    val artifact: ModrinthManifest.Artifact,
    val bytes: ByteArray,
) {
    /**
     * Decodes the fixture's complete two-part upload contract without using the client publisher.
     */
    companion object {
        /**
         * Rejects incomplete metadata, misplaced files and corrupted bytes before the fixture commits a version.
         */
        fun parse(
            contentType: String,
            body: ByteArray,
            fixture: ModrinthReleaseFixture,
        ): MockModrinthUpload {
            val prefix = "multipart/form-data; boundary="
            require(contentType.startsWith(prefix)) { "Upload requires a multipart boundary." }
            val boundary = contentType.removePrefix(prefix)
            require(boundary.isNotEmpty())
            val encoded = String(body, StandardCharsets.ISO_8859_1)
            require(encoded.startsWith("--$boundary\r\n"))
            val parts = encoded.removePrefix("--$boundary\r\n").split("\r\n--$boundary")
            require(parts.size == 3 && parts.last().contentEquals("--\r\n")) { "Upload requires data and primary parts." }
            val data = parts[0].split("\r\n\r\n", limit = 2)
            require(data.size == 2)
            require(data[0].lines() == listOf("Content-Disposition: form-data; name=\"data\"", "Content-Type: application/json"))
            val metadata = JsonSlurper().parseText(String(data[1].toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))
            require(metadata is Map<*, *>)
            val artifact = fixture.manifest.artifacts.single { metadata["version_number"] == it.versionNumber }
            val expected =
                mapOf(
                    "name" to artifact.versionName,
                    "version_number" to artifact.versionNumber,
                    "changelog" to fixture.manifest.changelog,
                    "dependencies" to listOf("Ha28R6CL", "P7dR8mSH").map { mapOf("project_id" to it, "dependency_type" to "required") },
                    "game_versions" to listOf(artifact.gameVersion),
                    "version_type" to "release",
                    "loaders" to listOf("fabric"),
                    "featured" to true,
                    "status" to "listed",
                    "project_id" to fixture.manifest.projectId,
                    "file_parts" to listOf("primary"),
                    "primary_file" to "primary",
                    "environment" to "client_only_server_optional",
                )
            require(metadata == expected) { "Upload metadata differs from the complete release contract." }
            val primary = parts[1].removePrefix("\r\n").split("\r\n\r\n", limit = 2)
            require(primary.size == 2)
            require(primary[0].lines() == listOf("Content-Disposition: form-data; name=\"primary\"; filename=\"${artifact.fileName}\"", "Content-Type: application/java-archive")) {
                "Upload primary part name, filename or content type differs."
            }
            val bytes = primary[1].toByteArray(StandardCharsets.ISO_8859_1)
            require(bytes.contentEquals(fixture.bundle.resolve(artifact.relativePath).readBytes())) { "Uploaded artifact bytes differ." }
            return MockModrinthUpload(artifact, bytes)
        }
    }
}
