package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Exercises the production runtime replacement action with real resolved project JARs and a loaded probe. */
internal class BoundedJvmRuntimeProjectionFunctionalTest {
    @TempDir
    lateinit var directory: Path

    private val runtimes = listOf("one", "two", "three", "four", "five")

    @Test
    fun `each owner loads its frozen runtime subset and rejects invalid matched archives`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }
        val production = Files.readString(repository.resolve("gradle/performance/bounded-jvm.init.gradle.kts"))
        val replacement =
            production
                .substringAfter("                val requested = rootProject.providers.gradleProperty(\"strata.paired.runtime\").orNull")
                .substringBefore("                rootProject.providers.gradleProperty(\"strata.performance.operationRequest\")")
                .substringBefore("                if (rootProject.providers.gradleProperty(\"strata.paired.classpath\").isPresent")
        write("settings.gradle.kts", "rootProject.name = \"runtime-projection\"\ninclude(${(runtimes + "support").joinToString { "\"$it\"" }})\n")
        write(
            "build.gradle.kts",
            """
            import org.gradle.api.artifacts.component.ProjectComponentIdentifier
            import org.gradle.api.attributes.LibraryElements
            import org.gradle.api.attributes.Usage
            import java.io.File
            import java.util.Properties
            plugins { java }
            subprojects { apply(plugin = "java-library") }
            val jmhRuntimeClasspath = configurations.create("jmhRuntimeClasspath") {
                isCanBeConsumed = false
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                }
            }
            val selected = providers.gradleProperty("fixture.runtimes").get().split(',')
            (selected + "support").forEach { dependencies.add(jmhRuntimeClasspath.name, project(":${'$'}it")) }
            tasks.register<JavaExec>("verifyProjection") {
                dependsOn("classes", jmhRuntimeClasspath)
                classpath = sourceSets["main"].runtimeClasspath + jmhRuntimeClasspath
                mainClass.set("fixture.Probe")
                args(selected + "support")
                maxHeapSize = "64m"
                doFirst {
                    val reportRequest: Map<*, *>? = null
                    val requested = rootProject.providers.gradleProperty("strata.paired.runtime").orNull
            $replacement
                }
            }
            """.trimIndent(),
        )
        write(
            "src/main/java/fixture/Probe.java",
            """
            package fixture;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            public class Probe {
                public static void main(String[] names) throws Exception {
                    for (String name : names) {
                        try (InputStream input = Probe.class.getResourceAsStream("/" + name + ".txt")) {
                            if (input == null) throw new IllegalStateException("Missing runtime resource: " + name);
                            String expected = name.equals("support") ? "untouched" : "candidate";
                            if (expected.equals(new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
                                System.out.println("loaded-" + name);
                            } else {
                                throw new IllegalStateException("Wrong runtime resource: " + name);
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        (runtimes + "support").forEach { name ->
            write("$name/build.gradle.kts", "")
            write("$name/src/main/resources/$name.txt", if (name == "support") "untouched" else "baseline")
        }
        val archives =
            runtimes.associate { name ->
                val archive = directory.resolve("frozen/$name.jar")
                Files.createDirectories(archive.parent)
                JarOutputStream(Files.newOutputStream(archive)).use { jar ->
                    jar.putNextEntry(JarEntry("$name.txt"))
                    jar.write("candidate".toByteArray())
                    jar.closeEntry()
                }
                ":$name" to archive
            }
        manifest(archives)
        val subset = runtimes.take(3)
        listOf(subset, runtimes).forEach { selected ->
            val result = runner(selected).build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":verifyProjection")?.outcome)
            assertTrue((selected + "support").all { result.output.contains("loaded-$it") }, result.output)
            assertTrue((runtimes - selected.toSet()).all { result.output.contains("loaded-$it").not() }, result.output)
            assertTrue(archives.values.all { Files.isRegularFile(it) })
        }
        listOf(
            archives + (":one" to directory.resolve("absent.jar")),
            archives + (":one" to archives.getValue(":two")),
            archives + (":one" to directory.resolve("support/build/libs/support.jar")),
            archives.mapKeys { (name, _) -> ":outside$name" },
        ).forEach { invalid ->
            manifest(invalid)
            val result = runner(subset).buildAndFail()
            assertEquals(TaskOutcome.FAILED, result.task(":verifyProjection")?.outcome)
            assertTrue(result.output.contains("loaded-one").not(), result.output)
        }
    }

    /** Retains the whole global archive mapping for every independently selected owner. */
    private fun manifest(archives: Map<String, Path>) {
        Files.newBufferedWriter(directory.resolve("runtime.properties")).use { output ->
            Properties().apply { archives.forEach { (name, path) -> setProperty(name, path.toString()) } }.store(output, "Frozen runtime union")
        }
    }

    /** Writes only synthetic working Java resources and their consumer before running the production action. */
    private fun write(
        relative: String,
        text: String,
    ) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /** Bounds the real dependency and JavaExec fixture without loading Minecraft or creating measurement evidence. */
    private fun runner(selected: List<String>): GradleRunner =
        GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
            "verifyProjection",
            "-Pfixture.runtimes=${selected.joinToString(",")}",
            "-Pstrata.paired.runtime=${directory.resolve("runtime.properties")}",
            "--max-workers=1",
            "--no-parallel",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
            "--stacktrace",
        )
}
