package dev.s7a.strata.performance

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

/**
 * Rejects live consumed-file changes and manifest redirection despite unchanged preserved JMH copies.
 */
internal class JvmOperationInputsTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun changedLiveBytesCannotBorrowTheIdentityOfAnUnchangedJmhCopy() {
        val archive = Files.write(directory.resolve("archive.bin"), byteArrayOf(1, 2, 3))
        val live = Files.copy(archive, directory.resolve("live.bin"))
        val expected = JsonObject().apply { addProperty("fixture", ArtifactIdentity.file(archive)) }
        val source = JvmOperationInputs.bind(mapOf("fixture" to live), expected)
        assertEquals(expected.textField("fixture"), source.objectField("fixture").textField("sha256"))
        Files.write(live, byteArrayOf(4, 5, 6))
        assertEquals(expected.textField("fixture"), ArtifactIdentity.file(archive))
        assertFails { JvmOperationInputs.bind(mapOf("fixture" to live), expected) }
        assertFails { JvmOperationInputs.bind(emptyMap(), expected) }
        assertFails { JvmOperationInputs.bind(mapOf("fixture" to archive, "unknown" to live), expected) }
    }

    @Test
    fun collectionAndProcessingRejectRedirectedDeclaredMappingsIncludingEqualBytes() {
        val library = Files.write(directory.resolve("library.jar"), byteArrayOf(1))
        val fixture = Files.write(directory.resolve("fixture.bin"), byteArrayOf(2))
        val redirected = Files.copy(fixture, directory.resolve("redirected.bin"))
        val controls = directory.resolve("controls.properties")
        val declared = directory.resolve("fixtures.properties")
        manifest(controls, "library", library)
        manifest(declared, "fixture", fixture)
        val expected =
            JsonObject().apply {
                addProperty("library", ArtifactIdentity.file(library))
                addProperty("fixture", ArtifactIdentity.file(fixture))
            }
        val properties = mapOf("strata.performance.inputs" to controls.toString(), "strata.performance.fixtureInputs" to declared.toString())
        val previous = properties.keys.associateWith(System::getProperty)
        try {
            properties.forEach { (name, value) -> System.setProperty(name, value) }
            val original = JvmOperationInputs.capture(emptyList(), expected)
            JvmOperationInputs.verify(original, expected)
            manifest(declared, "fixture", redirected)
            assertNotEquals(original, JvmOperationInputs.capture(emptyList(), expected))
            assertFails { JvmOperationInputs.verify(original, expected) }
            Files.write(redirected, byteArrayOf(3))
            assertFails { JvmOperationInputs.capture(emptyList(), expected) }
            manifest(declared, "unknown", fixture)
            assertFails { JvmOperationInputs.capture(emptyList(), expected) }
        } finally {
            previous.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
        }
    }

    private fun manifest(
        path: Path,
        label: String,
        source: Path,
    ) {
        Files.newBufferedWriter(path, Charsets.UTF_8).use { writer -> Properties().apply { setProperty(label, source.toString()) }.store(writer, null) }
    }
}
