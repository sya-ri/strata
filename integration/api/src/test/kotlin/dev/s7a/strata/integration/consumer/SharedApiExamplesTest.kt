package dev.s7a.strata.integration.consumer

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the compiled facades of every actual example selected for the API-only main source set.
 */
internal class SharedApiExamplesTest {
    @Test
    fun selectedSharedExamplesCompileWithTheApiOnlyMainClasspath() {
        val paths = checkNotNull(System.getProperty("strata.sharedApiExampleSources")).split(File.pathSeparator).filter(String::isNotBlank)
        assertTrue(paths.isNotEmpty())
        for (path in paths) {
            val source = Path.of(path)
            val packageName = checkNotNull(Regex("^package ([A-Za-z0-9_.]+)$", RegexOption.MULTILINE).find(Files.readString(source))).groupValues[1]
            val facadeName = "${source.fileName.toString().removeSuffix(".kt")}Kt"
            val facade = Class.forName("$packageName.$facadeName", false, javaClass.classLoader)
            assertTrue(facade.declaredMethods.isNotEmpty(), path)
        }
    }
}
