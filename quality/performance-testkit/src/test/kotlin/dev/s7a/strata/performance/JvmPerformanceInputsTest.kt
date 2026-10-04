package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers portable JDK escaping and rejects ambiguous or missing external fixture inputs.
 */
class JvmPerformanceInputsTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun escapedPathsAndLabelsResolveTheActualFile() {
        val input = Files.write(directory.resolve("日本語 font.bin"), byteArrayOf(1, 2, 3))
        val manifest = directory.resolve("inputs.properties")
        val properties = Properties().apply { setProperty("test:font:日本語", input.toString()) }
        Files.newBufferedWriter(manifest, Charsets.UTF_8).use { properties.store(it, null) }
        assertEquals(mapOf("test:font:日本語" to input), JvmPerformanceInputs.read(manifest))
    }

    @Test
    fun duplicateLabelsAndRelativeOrMissingPathsFail() {
        val manifest = directory.resolve("inputs.properties")
        for (content in listOf("font=first\nfont=second\n", "font=relative.bin\n", "font=\n")) {
            Files.writeString(manifest, content)
            assertFailsWith<IllegalArgumentException> { JvmPerformanceInputs.read(manifest) }
        }
        val properties = Properties().apply { setProperty("font", directory.resolve("missing.bin").toString()) }
        Files.newBufferedWriter(manifest, Charsets.UTF_8).use { properties.store(it, null) }
        assertFailsWith<IllegalArgumentException> { JvmPerformanceInputs.read(manifest) }
    }
}
