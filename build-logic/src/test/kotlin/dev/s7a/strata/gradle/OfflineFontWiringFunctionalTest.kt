package dev.s7a.strata.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Executes the production selected-check wiring and complete font verification script with tiny native and JUnit producers.
 * Repeated builds retain ordinary compilation outputs while recreating acceptance evidence.
 */
internal class OfflineFontWiringFunctionalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun selectedChecksExecuteFreshNativeOfflineAndComparisonTasksInOrder() {
        prepareFixture()
        val contracts = mapOf("1.20" to "Minecraft120", "1.20.5" to "Minecraft1205", "26.3" to "Minecraft263")
        for ((release, contract) in contracts) {
            val native = ":integration:minecraft-fabric-$release:runClientGameTest"
            val offline = ":runtime:minecraft-fonts-lwjgl:fontTest$contract"
            val comparison = ":runtime:minecraft-fonts-lwjgl:compareOfflineFont$contract"
            val runner =
                GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
                    ":integration:minecraft-fabric-$release:check",
                    "--build-cache",
                    "--max-workers=1",
                    "--stacktrace",
                    "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=384m",
                )
            repeat(2) {
                val result = runner.build()
                for (task in listOf(native, offline, comparison)) {
                    assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, "$task must execute on every invocation")
                }
                val executed = result.tasks.map { it.path }
                assertTrue(executed.indexOf(native) < executed.indexOf(comparison))
                assertTrue(executed.indexOf(offline) < executed.indexOf(comparison))
                assertEquals("compared", Files.readString(directory.resolve("runtime/minecraft-fonts-lwjgl/build/font-offline-parity/$release/comparison.txt")))
            }
        }
    }

    private fun prepareFixture() {
        val repository =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }
        write(
            "settings.gradle.kts",
            """
            rootProject.name = "offline-font-wiring"
            include(":runtime:minecraft-fonts-lwjgl", ":integration:minecraft-fabric-1.20", ":integration:minecraft-fabric-1.20.5", ":integration:minecraft-fabric-26.3")
            """.trimIndent(),
        )
        for (release in listOf("1.20", "1.20.5", "26.3")) Files.createDirectories(directory.resolve("integration/minecraft-fabric-$release"))
        write("gradle.properties", "org.gradle.kotlin.dsl.allWarningsAsErrors=true")
        Files.createDirectories(directory.resolve("gradle"))
        Files.copy(repository.resolve("gradle/libs.versions.toml"), directory.resolve("gradle/libs.versions.toml"))
        Files.copy(repository.resolve("runtime/minecraft-fonts-lwjgl/verification.gradle.kts"), directory.resolve("verification.gradle.kts"))
        val production = Files.readString(repository.resolve("build.gradle.kts")).replace("\r\n", "\n")
        val mapping = production.substringAfter("private val fontParityComparisonsByVersion =").substringBefore("private val fontParityMinecraftVersions")
        val start = production.indexOf("            fontParityComparisonsByVersion[target.version]?.let")
        check(0 <= start) { "The selected-check production configuration is missing" }
        val wiring = production.substring(start, production.indexOf("\n            }", start) + "\n            }".length)
        write(
            "build.gradle.kts",
            resource("build.gradle.kts.txt")
                .replace("__PARITY_MAPPING__", "val fontParityComparisonsByVersion = $mapping")
                .replace("__SELECTED_CHECK_WIRING__", wiring),
        )
        write("runtime/minecraft-fonts-lwjgl/build.gradle.kts", resource("font-build.gradle.kts.txt"))
        write("runtime/minecraft-fonts-lwjgl/src/test/java/FixtureTest.java", resource("FixtureTest.java.txt"))
    }

    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/offline-font-wiring/$name")).readText()

    private fun write(
        relative: String,
        content: String,
    ) {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimEnd() + "\n")
    }
}
