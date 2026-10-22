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
        write(
            "build.gradle.kts",
            $$"""
            import dev.detekt.gradle.Detekt
            import dev.detekt.gradle.extensions.DetektExtension
            import org.gradle.api.tasks.SourceSetContainer
            import org.gradle.api.tasks.testing.Test
            import org.gradle.jvm.toolchain.JavaLanguageVersion
            import org.gradle.jvm.toolchain.JavaToolchainService
            import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

            plugins {
                alias(libs.plugins.kotlin.jvm) apply false
                alias(libs.plugins.detekt) apply false
            }
            subprojects {
                apply(plugin = "org.jetbrains.kotlin.jvm")
                apply(plugin = "dev.detekt")
                repositories { mavenCentral() }
                extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
                extensions.configure<DetektExtension> {
                    source.setFrom(providers.provider {
                        this@subprojects.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.map { it.kotlin }
                    })
                }
                val javaVersion = 17
            $$declaration
            }
            project(":integration:paper") {
                dependencies {
                    add("implementation", project(":library"))
                    add("testImplementation", rootProject.libs.junit.jupiter)
                    add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
                }
                extensions.configure<KotlinJvmProjectExtension> {
                    sourceSets.named("main") { kotlin.srcDir(rootProject.file("host-main")) }
                    sourceSets.named("test") { kotlin.srcDir(rootProject.file("host-test")) }
                }
                tasks.withType<Test>().configureEach { useJUnitPlatform() }
                if (providers.gradleProperty("fixture.combineSourceContexts").isPresent) {
                    tasks.named<Detekt>("detektMain") {
                        source(project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.named("test").map { it.kotlin })
                        debug.set(true)
                    }
                }
            }
            tasks.register("verifyHostDetektCompilations") {
                dependsOn(":integration:paper:detekt", ":integration:paper:test")
                doLast {
                    val host = project(":integration:paper")
                    val main = host.tasks.named<Detekt>("detektMain").get()
                    val test = host.tasks.named<Detekt>("detektTest").get()
                    check(main.source.files == setOf(file("host-main/fixture/HostValue.kt"))) { "Production analysis must not include test inputs" }
                    check(test.source.files == setOf(file("host-test/fixture/LibraryValue.kt"), file("host-test/fixture/HostContextTest.kt"))) { "Test analysis lost its authored inputs" }
                    check(main.classpath.none { it.name.startsWith("junit-jupiter-api") }) { "Test libraries entered production analysis" }
                    check(test.classpath.any { it.name.startsWith("junit-jupiter-api") }) { "Test analysis lost its compiler library" }
                }
            }
            """.trimIndent(),
        )
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
