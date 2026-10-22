package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies real Kotlin/Detekt file selection with late shared roots and compiler filters, without Minecraft.
 */
internal class GameTestDetektInputsFunctionalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `analyze every selected game test and preserve filters and ordinary sources`() {
        val repository =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }
        Files.createDirectories(directory.resolve("gradle"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        val production = Files.readString(repository.resolve("build.gradle.kts")).replace("\r\n", "\n")
        val marker = "        source.setFrom(providers.provider {\n            this@subprojects.extensions.getByType<KotlinJvmProjectExtension>()"
        val start = production.indexOf(marker)
        check(0 <= start) { "The production Kotlin source-set analysis declaration is absent." }
        val declaration = production.substring(start, production.indexOf("\n        })", start) + "\n        })".length)
        write("settings.gradle.kts", "rootProject.name = \"game-test-analysis\"\ninclude(\"owner\")\n")
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true\n")
        writeBuild(declaration)
        write("owner/src/main/kotlin/Ordinary.kt", "internal class Ordinary\n")
        write("owner/docs/ReadmeExample.kt", "internal class ReadmeExample\n")
        write("owner/authored/SelectedGame.kt", "internal class SelectedGame\n")
        write("owner/authored/ExcludedGame.kt", "This file must remain outside compilation and analysis.\n")
        write("shared/SelectedExample.kt", "internal class SelectedExample\n")
        write("shared/ExcludedExample.kt", "This excluded example must remain outside compilation and analysis.\n")
        write("shared/Ignored.kt", "This non-example must remain outside compilation and analysis.\n")
        val result =
            GradleRunner
                .create()
                .withProjectDir(directory.toFile())
                .withArguments(
                    ":owner:verifyDetektSelection",
                    "--no-configure-on-demand",
                    "--max-workers=2",
                    "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
                    "--stacktrace",
                ).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":owner:compileGametestKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":owner:verifyDetektSelection")?.outcome)
    }

    /**
     * Applies the current production source declaration to late, filtered compiler roots.
     */
    private fun writeBuild(declaration: String) {
        write(
            "build.gradle.kts",
            $$"""
            import dev.detekt.gradle.Detekt
            import dev.detekt.gradle.extensions.DetektExtension
            import org.gradle.api.tasks.SourceSetContainer
            import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

            plugins {
                alias(libs.plugins.kotlin.jvm) apply false
                alias(libs.plugins.detekt) apply false
            }
            subprojects {
                apply(plugin = "org.jetbrains.kotlin.jvm")
                apply(plugin = "dev.detekt")
                repositories { mavenCentral() }
                extensions.configure<DetektExtension> {
            $$declaration
                }
                extensions.getByType<SourceSetContainer>().create("gametest")
                extensions.getByType<SourceSetContainer>().create("readmeExamples")
                val shared = objects.sourceDirectorySet("sharedExamples", "Filtered shared examples").apply {
                    srcDir(rootProject.file("shared"))
                    include("**/*Example.kt")
                    exclude("**/ExcludedExample.kt")
                }
                extensions.configure<KotlinJvmProjectExtension> {
                    jvmToolchain(17)
                    sourceSets.named("readmeExamples") { kotlin.setSrcDirs(listOf("docs")) }
                    sourceSets.named("gametest") {
                        kotlin.setSrcDirs(listOf("authored"))
                        kotlin.exclude("**/ExcludedGame.kt")
                        kotlin.source(shared)
                    }
                }
                tasks.register("verifyDetektSelection") {
                    dependsOn("compileGametestKotlin", "compileReadmeExamplesKotlin")
                    doLast {
                        val selected = project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.getByName("gametest").kotlin.files
                        val analyzed = tasks.named<Detekt>("detekt").get().source.files
                        val expected = setOf(file("authored/SelectedGame.kt"), rootProject.file("shared/SelectedExample.kt"))
                        check(selected == expected) { "The working compiler selection changed: $selected" }
                        check(analyzed == expected + file("src/main/kotlin/Ordinary.kt") + file("docs/ReadmeExample.kt")) { "Analysis lost declared Kotlin sources: $analyzed" }
                        check(analyzed.none { it.name.startsWith("Excluded") || it.name == "Ignored.kt" }) { "Analysis broadened a compiler filter: $analyzed" }
                    }
                }
            }
            """.trimIndent(),
        )
    }

    /**
     * Writes authored fixture files before the real Gradle configuration and compilation.
     */
    private fun write(
        relative: String,
        text: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }
}
