package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps typed host analysis independent from test-only libraries and shadowing test classes.
 */
internal class HostDetektCompilationFunctionalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `resolve host and test inputs in separate compiler contexts`() {
        val repository =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }
        Files.createDirectories(directory.resolve("gradle"))
        Files.createDirectories(directory.resolve("integration/paper"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        val production = Files.readString(repository.resolve("build.gradle.kts")).replace("\r\n", "\n")
        val marker = "    if (path in setOf(\":integration:paper\", \":integration:velocity\")) {"
        val start = production.indexOf(marker)
        check(0 <= start) { "The production host analysis declaration is absent." }
        val declaration = production.substring(start, production.indexOf("\n    extensions.configure<JavaPluginExtension>", start))
        write("settings.gradle.kts", "rootProject.name = \"host-analysis\"\ninclude(\"library\", \"integration:paper\")\n")
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true\n")
        writeBuild(declaration)
        write("library/src/main/kotlin/fixture/LibraryValue.kt", "package fixture\n/** Original production dependency carrying an integer. */\npublic data class LibraryValue(public val original: Int)\n")
        write("host-main/fixture/HostValue.kt", "package fixture\n/** Uses the original dependency outside the default source directory. */\ninternal fun hostValue(): Int = LibraryValue(1).original\n")
        write("host-test/fixture/LibraryValue.kt", "package fixture\n/** Deliberately shadows the production dependency with a distinct test value. */\ninternal data class LibraryValue(val stub: String)\n")
        write(
            "host-test/fixture/HostContextTest.kt",
            """
            package fixture
            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Test
            /** Consumes a test-only library and the local shadow without invoking production bytecode. */
            internal class HostContextTest {
                @Test
                fun resolvesTestContext() { assertEquals("test", LibraryValue("test").stub) }
            }
            """.trimIndent(),
        )
        val runner =
            GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
                "verifyHostDetektCompilations",
                "--no-configure-on-demand",
                "--max-workers=1",
                "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=384m",
                "--stacktrace",
            )
        val result = runner.build()
        listOf("detektMain", "detektTest", "test").forEach { task ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":integration:paper:$task")?.outcome)
        }
        assertFalse(result.output.contains("compiler errors found during analysis"))
        val poisoned = runner.withArguments(runner.arguments + "-Pfixture.combineSourceContexts=true").buildAndFail()
        assertTrue(poisoned.output.contains("compiler errors found during analysis"))
        assertTrue(poisoned.output.contains("Production analysis must not include test inputs"))
    }

    /**
     * Applies the production host configuration to late external roots and a real test-only library.
     */
    private fun writeBuild(declaration: String) {
        val template = requireNotNull(javaClass.getResource("/host-detekt-compilation/build.gradle.kts.txt")).readText()
        write("build.gradle.kts", template.replace("__HOST_ANALYSIS_DECLARATION__", declaration))
    }

    /**
     * Writes one fixture input before Gradle resolves its compiler or analysis tasks.
     */
    private fun write(
        relative: String,
        content: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content.trimEnd() + "\n")
    }
}
