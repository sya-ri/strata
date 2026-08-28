package dev.s7a.strata.gradle.fabric

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Exercises toolchain identity verification with tiny temporary archives, without Gradle or Minecraft.
 */
internal class FabricToolchainManifestTest {
    @TempDir
    lateinit var directory: Path

    private val expected: Map<String, String> =
        mapOf(
            "Fabric-Loader-Version" to "test-loader",
            "Fabric-Mixin-Version" to "test-mixin",
            "Fabric-Mixin-Group" to "example.mixin",
        )
    private val verifier = FabricToolchainManifest("test-loader", "test-mixin", "example.mixin")

    @Test
    fun `exact identities accept unrelated attributes without closing or rewriting the archive`() {
        val manifest = manifest(expected + ("Implementation-Title" to "Tiny fixture")) + "Name: payload.txt\r\nCustom-Attribute: retained\r\n\r\n"
        val path = archive(JarFile.MANIFEST_NAME to manifest, "payload.txt" to "payload")
        val before = Files.readAllBytes(path)
        TrackingZipFile(path.toFile()).use { zip ->
            verifier.verify(zip)
            assertEquals(1, zip.openedStreams)
            assertEquals(1, zip.closedStreams)
            assertEquals(2, zip.size())
            assertEquals("payload", zip.getInputStream(zip.getEntry("payload.txt")).bufferedReader().use { it.readText() })
        }
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test
    fun `each missing main identity is rejected`() {
        for (name in expected.keys) {
            val path = archive(JarFile.MANIFEST_NAME to manifest(expected - name))
            ZipFile(path.toFile()).use { zip ->
                assertThrows(IllegalStateException::class.java) { verifier.verify(zip) }
            }
        }
    }

    @Test
    fun `empty unknown and wrong values cannot satisfy any identity`() {
        for (name in expected.keys) {
            for (value in listOf("", " ", "unknown", "wrong-identity")) {
                assertRejected(manifest(expected + (name to value)))
            }
        }
    }

    @Test
    fun `named section identities cannot substitute for main attributes`() {
        val named = "Manifest-Version: 1.0\r\n\r\nName: payload.txt\r\n" + attributes(expected) + "\r\n"
        assertRejected(named)
    }

    @Test
    fun `an archive without a manifest is rejected before opening a stream`() {
        val path = archive("payload.txt" to "payload")
        TrackingZipFile(path.toFile()).use { zip ->
            assertThrows(IllegalStateException::class.java) { verifier.verify(zip) }
            assertEquals(0, zip.openedStreams)
            assertEquals(1, zip.size())
        }
    }

    @Test
    fun `duplicate exact manifest entries are rejected before opening a stream`() {
        val alternate = "META-INF/MANIFEST.XF"
        val path = archive(JarFile.MANIFEST_NAME to manifest(expected), alternate to manifest(expected))
        replaceEntryName(path, alternate, JarFile.MANIFEST_NAME)
        TrackingZipFile(path.toFile()).use { zip ->
            assertEquals(2, zip.entries().asSequence().count { it.name == JarFile.MANIFEST_NAME })
            assertThrows(IllegalStateException::class.java) { verifier.verify(zip) }
            assertEquals(0, zip.openedStreams)
        }
    }

    @Test
    fun `malformed manifest parsing closes the entry stream but not the archive`() {
        val path = archive(JarFile.MANIFEST_NAME to "Manifest-Version: 1.0\r\ninvalid header\r\n\r\n")
        TrackingZipFile(path.toFile()).use { zip ->
            assertThrows(IOException::class.java) { verifier.verify(zip) }
            assertEquals(1, zip.openedStreams)
            assertEquals(1, zip.closedStreams)
            assertEquals(1, zip.size())
        }
    }

    @Test
    fun `identity validation failure closes the entry stream but not the archive`() {
        val path = archive(JarFile.MANIFEST_NAME to manifest(expected - "Fabric-Mixin-Version"))
        TrackingZipFile(path.toFile()).use { zip ->
            assertThrows(IllegalStateException::class.java) { verifier.verify(zip) }
            assertEquals(1, zip.openedStreams)
            assertEquals(1, zip.closedStreams)
            assertEquals(1, zip.size())
        }
    }

    @Test
    fun `blank expected identities are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { FabricToolchainManifest("", "test-mixin", "example.mixin") }
        assertThrows(IllegalArgumentException::class.java) { FabricToolchainManifest("test-loader", " ", "example.mixin") }
        assertThrows(IllegalArgumentException::class.java) { FabricToolchainManifest("test-loader", "test-mixin", "") }
    }

    /**
     * Verifies one invalid temporary manifest and releases its caller-owned archive.
     */
    private fun assertRejected(manifest: String) {
        val path = archive(JarFile.MANIFEST_NAME to manifest)
        ZipFile(path.toFile()).use { zip ->
            assertThrows(IllegalStateException::class.java) { verifier.verify(zip) }
        }
    }

    /**
     * Encodes standard main attributes without any named sections.
     */
    private fun manifest(values: Map<String, String>): String = "Manifest-Version: 1.0\r\n" + attributes(values) + "\r\n"

    /**
     * Encodes each borrowed fixture attribute in its declared iteration order.
     */
    private fun attributes(values: Map<String, String>): String = values.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }

    /**
     * Creates one tiny owned ZIP fixture and closes its output before returning the path.
     */
    private fun archive(vararg entries: Pair<String, String>): Path {
        val path = Files.createTempFile(directory, "toolchain-", ".jar")
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name))
                output.write(value.toByteArray())
                output.closeEntry()
            }
        }
        return path
    }

    /**
     * Produces a duplicate-name ZIP by replacing only equal-length local and central-directory entry names.
     * ZipOutputStream itself rejects duplicate names, so the fixture verifies both metadata replacements explicitly.
     */
    private fun replaceEntryName(
        path: Path,
        previous: String,
        replacement: String,
    ) {
        val bytes = Files.readAllBytes(path)
        val needle = previous.toByteArray()
        val next = replacement.toByteArray()
        assertEquals(needle.size, next.size)
        var replacements = 0
        for (index in 0..bytes.size - needle.size) {
            if (needle.indices.all { offset -> bytes[index + offset] == needle[offset] }) {
                next.copyInto(bytes, index)
                replacements++
            }
        }
        assertEquals(2, replacements)
        Files.write(path, bytes)
    }

    /**
     * Tracks entry-stream ownership while keeping the real ZIP parser and caller-owned archive lifecycle.
     */
    private class TrackingZipFile(
        file: File,
    ) : ZipFile(file) {
        var openedStreams: Int = 0
            private set

        var closedStreams: Int = 0
            private set

        override fun getInputStream(entry: ZipEntry): InputStream {
            val stream = super.getInputStream(entry)
            openedStreams++
            return object : FilterInputStream(stream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        closedStreams++
                        assertTrue(closedStreams <= openedStreams)
                    }
                }
            }
        }
    }
}
