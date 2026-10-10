package dev.s7a.strata.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Runs the shared production JMH export and inventory against working, Minecraft-independent owners. */
internal class QodanaJmhModelFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val repository =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }

    @Test
    fun `discover custom working owners and retain all declared inputs and dependency mappings`() {
        Files.createDirectories(directory.resolve("gradle"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true\n")
        val temporaryOwner = "working-${directory.fileName.toString().filter(Char::isLetterOrDigit)}"
        val owners = listOf("bench-one", "bench-two", temporaryOwner)
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\ninclude(\"support\", ${owners.joinToString { "\"$it\"" }})\n")
        val production = Files.readString(repository.resolve("build.gradle.kts"))
        val inventory = production.substringAfter("if (completeIdeaModelActive) {\n    apply(plugin = \"idea\")\n").substringBefore("\n}\n\nallprojects")
        val callback = production.substringAfter("    plugins.withId(\"me.champeau.jmh\") {").substringBefore("        val analysisJava")
        Files.writeString(
            directory.resolve("build.gradle.kts"),
            """
            import groovy.json.JsonOutput
            import java.io.File
            import java.security.MessageDigest
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier
            import org.gradle.api.artifacts.component.ProjectComponentIdentifier
            import org.gradle.api.plugins.JavaPluginExtension
            import org.gradle.api.tasks.SourceSetContainer
            import org.gradle.jvm.toolchain.JavaLanguageVersion
            import org.gradle.jvm.toolchain.JavaToolchainService
            import org.gradle.plugins.ide.idea.model.IdeaModel
            import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
            plugins {
                idea
                alias(libs.plugins.kotlin.jvm) apply false
                alias(libs.plugins.jmh) apply false
            }
            val completeIdeaModelActive = true
            $inventory
            subprojects {
                apply(plugin = "org.jetbrains.kotlin.jvm")
                apply(plugin = "idea")
                repositories { mavenCentral() }
                extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
                plugins.withId("me.champeau.jmh") {
            $callback
                }
            }
            """.trimIndent(),
        )
        write("support/build.gradle.kts", "")
        write("support/src/main/java/fixture/Support.java", "package fixture; public class Support { public static int value() { return 7; } }")
        write("shared-inputs/kotlin/fixture/SharedValue.kt", "package fixture\ninternal fun sharedValue(): Int = 11\n")
        write("shared-inputs/resources/shared.txt", "shared compilation resource\n")
        owners.forEach { owner ->
            write(
                "$owner/build.gradle.kts",
                """
                plugins { alias(libs.plugins.jmh) }
                dependencies { add("jmh", project(":support")) }
                sourceSets.named("jmh") {
                    java.setSrcDirs(listOf("authored/java"))
                    resources.setSrcDirs(listOf("authored/resources"))
                }
                val additionalJmh = kotlin.sourceSets.create("additionalJmh") {
                    kotlin.setSrcDirs(listOf(rootProject.file("shared-inputs/kotlin")))
                    resources.setSrcDirs(listOf(rootProject.file("shared-inputs/resources")))
                }
                kotlin.sourceSets.named("jmh") {
                    kotlin.setSrcDirs(listOf("authored/kotlin"))
                    dependsOn(additionalJmh)
                }
                idea.module.name = "resolved-${'$'}{project.name}"
                """.trimIndent(),
            )
            write("$owner/authored/java/fixture/JavaBenchmark.java", "package fixture; import org.openjdk.jmh.annotations.Benchmark; public class JavaBenchmark { @Benchmark public int value() { return Support.value(); } }")
            write("$owner/src/main/kotlin/fixture/InternalValue.kt", "package fixture\ninternal fun internalValue(): Int = 7\n")
            write("$owner/authored/kotlin/fixture/KotlinBenchmark.kt", "package fixture\nimport org.openjdk.jmh.annotations.Benchmark\npublic open class KotlinBenchmark { @Benchmark public fun value(): Int = internalValue() + sharedValue() + Support.value() }\n")
            write("$owner/authored/resources/payload.txt", "working JMH resource\n")
        }
        git("init", "-q")
        git("add", ".")
        git("-c", "commit.gpgsign=false", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "Create working JMH owners")
        val result = runner().build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":qodanaDeclarationInventory")?.outcome)
        val parsed = JsonSlurper().parse(directory.resolve("build/qodana/declarations.json").toFile()) as Map<*, *>
        val inventoried = parsed["owners"] as List<*>
        assertEquals(owners.map { ":$it" }.toSet(), inventoried.map { (it as Map<*, *>)["project"] }.toSet())
        inventoried.forEach { value ->
            val owner = value as Map<*, *>
            val name = (owner["project"] as String).removePrefix(":")
            assertEquals("resolved-$name", owner["module"])
            val roots = owner["roots"] as List<*>
            val existing = roots.map { it as Map<*, *> }.filter { it["exists"] == true }
            assertEquals(setOf("$name/src/main/kotlin", "$name/authored/java", "$name/authored/kotlin", "$name/authored/resources", "shared-inputs/kotlin", "shared-inputs/resources"), existing.map { it["path"] }.toSet())
            val iml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve(owner["iml"] as String).toFile())
            val folders = iml.getElementsByTagName("sourceFolder")
            assertEquals(6, folders.length)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:compileJmhKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:compileJmhJava")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$name:processJmhResources")?.outcome)
            val dependencies = owner["dependencies"] as Map<*, *>
            assertEquals(setOf("jmhCompileClasspath", "jmhRuntimeClasspath"), dependencies.keys)
            dependencies.values.forEach { closure ->
                val entries = (closure as List<*>).map { it as Map<*, *> }
                assertTrue(entries.any { it["kind"] == "Module" && it["identity"] == "support" })
                assertTrue(entries.any { (it["identity"] as String).startsWith("org.openjdk.jmh:jmh-core:") })
            }
        }
        val reused = runner().build()
        assertEquals(TaskOutcome.SUCCESS, reused.task(":qodanaDeclarationInventory")?.outcome)
        assertEquals(parsed, JsonSlurper().parse(directory.resolve("build/qodana/declarations.json").toFile()))
    }

    /** Writes authored fixture sources before the declaration snapshot is selected. */
    private fun write(
        relative: String,
        text: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /** Creates an unsigned fixture repository without invoking workstation signing. */
    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    /** Bounds the real plugin/model fixture without loading Minecraft or benchmark harness generation. */
    private fun runner(): GradleRunner =
        GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
            "cleanIdea",
            "idea",
            "qodanaDeclarationInventory",
            "--no-configure-on-demand",
            "--max-workers=1",
            "--no-parallel",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
            "--stacktrace",
        )
}
