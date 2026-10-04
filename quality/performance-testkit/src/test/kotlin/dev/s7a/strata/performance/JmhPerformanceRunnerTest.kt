package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rejects unsupported fork ownership and missing external inputs before executing any benchmark.
 */
class JmhPerformanceRunnerTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun childContextCannotCertifyTargetsFromAnotherForkClasspath() {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        URLClassLoader(emptyArray(), previous).use { child ->
            try {
                thread.contextClassLoader = child
                val failure = assertFailsWith<IllegalArgumentException> { run() }
                assertTrue(checkNotNull(failure.message).contains("application/context classloader"))
                assertFalse(Files.exists(directory.resolve("results")))
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    @Test
    fun missingInputFailsBeforeCreatingAnEvidenceDirectory() {
        val failure = assertFailsWith<IllegalArgumentException> { run(mapOf("font" to directory.resolve("missing.ttf"))) }
        assertTrue(checkNotNull(failure.message).contains("not a regular file"))
        assertFalse(Files.exists(directory.resolve("results")))
    }

    @Test
    fun redirectedForkCannotCertifyParentArtifacts() {
        listOf("-cp other.jar", "--class-path=other.jar", "-Xbootclasspath/a:other.jar", "-javaagent:other.jar", "-Djava.system.class.loader=OtherLoader").forEach { argument ->
            val failure = assertFailsWith<IllegalArgumentException> { run(arguments = arrayOf("-jvmArgsAppend", argument)) }
            assertTrue(checkNotNull(failure.message).contains("fork classpath"))
            assertFalse(Files.exists(directory.resolve("results")))
        }
    }

    @Test
    fun unForkedInvocationCannotCertifyIndependentEvidence() {
        val failure = assertFailsWith<IllegalArgumentException> { run(arguments = arrayOf("-f", "0")) }
        assertTrue(checkNotNull(failure.message).contains("independent fork"))
        assertFalse(Files.exists(directory.resolve("results")))
    }

    private fun run(
        inputs: Map<String, Path> = emptyMap(),
        arguments: Array<String> = emptyArray(),
    ) {
        JmhPerformanceRunner.run(
            arguments,
            listOf(JmhPerformanceRunnerTest::class.java),
            mapOf("kit" to JvmPerformanceMeter::class.java.name),
            directory.resolve("results"),
            0,
            setOf("fixture"),
            inputs,
        )
    }
}
