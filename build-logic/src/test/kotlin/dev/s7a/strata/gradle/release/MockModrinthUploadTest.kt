package dev.s7a.strata.gradle.release

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Proves that malformed writes cannot obtain a synthesized successful upload response.
 */
internal class MockModrinthUploadTest : ModrinthReleaseTest() {
    @Test
    fun `upload oracle rejects metadata filename missing parts and corrupted bytes`() {
        val fixture = fixture()
        val server = server(fixture)
        val artifact = fixture.manifest.artifacts.first()
        val file = fixture.bundle.resolve(artifact.relativePath)
        fixture.client(server).createListedVersion(fixture.manifest, artifact, file)
        val contentType = checkNotNull(server.lastUploadContentType)
        val body = checkNotNull(server.lastUploadBody)
        assertArrayEquals(file.readBytes(), MockModrinthUpload.parse(contentType, body, fixture).bytes)
        val text = String(body, StandardCharsets.ISO_8859_1)
        val boundary = contentType.substringAfter("boundary=")
        val controls =
            listOf(
                text.replace(artifact.versionName, "wrong release name"),
                text.replace("Ha28R6CL", "wrong-dependency"),
                text.replace("\"primary_file\":\"primary\"", "\"primary_file\":\"missing\""),
                text.replace("filename=\"${artifact.fileName}\"", "filename=\"wrong.jar\""),
                text.replace(String(file.readBytes(), StandardCharsets.ISO_8859_1), "corrupt artifact"),
                text.substringBefore("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"primary\"") + "\r\n--$boundary--\r\n",
            )
        controls.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                MockModrinthUpload.parse(contentType, invalid.toByteArray(StandardCharsets.ISO_8859_1), fixture)
            }
        }
    }
}
