package dev.s7a.strata.gradle.release

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
        val http = HttpClient.newHttpClient()
        val download = http.send(HttpRequest.newBuilder(URI("${server.baseUrl}/cdn/${artifact.fileName}")).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, download.statusCode())
        assertArrayEquals(file.readBytes(), download.body())
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
            val rejecting = server(fixture)
            val request =
                HttpRequest
                    .newBuilder(URI("${rejecting.baseUrl}/v2/version"))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(invalid.toByteArray(StandardCharsets.ISO_8859_1)))
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(400, response.statusCode())
            assertTrue(response.body().contains("Upload"))
            assertEquals(0, rejecting.createRequests)
            assertTrue(rejecting.writeEvents.isEmpty())
        }
    }
}
