package dev.s7a.strata.integration.consumer

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies exact staged bytes and compiled facades for every actual example selected for the API-only main source set.
 */
internal class SharedApiExamplesTest {
    @Test
    fun selectedSharedExamplesCompileWithTheApiOnlyMainClasspath() {
        val paths = checkNotNull(System.getProperty("strata.sharedApiExampleSources")).split(File.pathSeparator).filter(String::isNotBlank)
        assertTrue(paths.isNotEmpty())
        val stagedRoot = Path.of(checkNotNull(System.getProperty("strata.stagedSharedApiExamples")))
        val stagedFiles = Files.walk(stagedRoot).use { files -> files.filter { Files.isRegularFile(it) }.count() }
        assertEquals(paths.size.toLong(), stagedFiles)
        for (path in paths) {
            val source = Path.of(path)
            val packageName = checkNotNull(Regex("^package ([A-Za-z0-9_.]+)$", RegexOption.MULTILINE).find(Files.readString(source))).groupValues[1]
            val staged = stagedRoot.resolve(packageName.replace('.', '/')).resolve(source.fileName)
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(staged), path)
            val facadeName = "${source.fileName.toString().removeSuffix(".kt")}Kt"
            val facade = Class.forName("$packageName.$facadeName", false, javaClass.classLoader)
            assertTrue(facade.declaredMethods.isNotEmpty(), path)
        }
    }
}
