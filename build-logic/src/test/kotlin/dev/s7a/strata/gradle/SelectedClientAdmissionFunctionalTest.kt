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
 * Exercises client admission against real task graphs without loading Minecraft.
 */
internal class SelectedClientAdmissionFunctionalTest {
    private val owner = ":integration:minecraft-fabric-26.3"

    @TempDir
    lateinit var directory: Path

    @Test
    fun `both backends admit indirect dependencies and ignore included builds`() {
        prepareFixture()
        listOf("opengl", "vulkan").forEach { backend ->
            val result = runner(backend).build()
            assertEquals(TaskOutcome.SUCCESS, result.task("$owner:runClientGameTest")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task("$owner:runProductionClientGameTest")?.outcome)
            assertTrue(result.output.contains("Admitted Minecraft 26.3 full $backend development and production clients."))
        }
    }

    @Test
    fun `missing dependency fails before either client executes`() {
        prepareFixture()
        val result = runner("opengl", "-Pfixture.omitProduction=true").buildAndFail()
        assertTrue(result.output.contains("Selected check omits $owner:runProductionClientGameTest."), result.output)
        assertFalse(result.output.contains("CLIENT EXECUTED"), result.output)
    }

    @Test
    fun `disabled task and mismatched inputs fail before client execution`() {
        prepareFixture()
        mapOf(
            "disableProduction" to "Selected client task is disabled",
            "wrongBackend" to "does not declare full vulkan client verification",
            "partialScope" to "does not declare full vulkan client verification",
            "missingInputs" to "does not declare full vulkan client verification",
        ).forEach { (failure, message) ->
            val result = runner("vulkan", "-Pfixture.$failure=true").buildAndFail()
            assertTrue(result.output.contains(message), result.output)
            assertFalse(result.output.contains("CLIENT EXECUTED"), result.output)
        }
    }

    /**
     * Creates tasks with observable actions and provider-backed inputs, plus an unrelated included build.
     */
    private fun prepareFixture() {
        Files.writeString(
            directory.resolve("settings.gradle.kts"),
            """
            rootProject.name = "selected-clients"
            include(":integration:minecraft-fabric-26.3")
            includeBuild("tools") { name = "unrelated-tools" }
            """.trimIndent(),
        )
        val tools = Files.createDirectories(directory.resolve("tools"))
        Files.writeString(tools.resolve("settings.gradle.kts"), "rootProject.name = \"unrelated-tools\"")
        Files.writeString(tools.resolve("build.gradle.kts"), "tasks.register(\"prepare\")")
        val integration = Files.createDirectories(directory.resolve("integration/minecraft-fabric-26.3"))
        Files.writeString(
            integration.resolve("build.gradle.kts"),
            """
            val backend = providers.gradleProperty("strata.canvas.backend")
            val clients = listOf("runClientGameTest", "runProductionClientGameTest").map { name ->
                tasks.register(name) {
                    dependsOn(gradle.includedBuild("unrelated-tools").task(":prepare"))
                    if (providers.gradleProperty("fixture.missingInputs").isPresent.not()) {
                        inputs.property("strataCanvasBackend", if (providers.gradleProperty("fixture.wrongBackend").isPresent) "default" else backend.get())
                        inputs.property("strataCanvasSuiteScope", if (providers.gradleProperty("fixture.partialScope").isPresent) "canvas-only" else "full")
                    }
                    enabled = (name == "runProductionClientGameTest" && providers.gradleProperty("fixture.disableProduction").isPresent).not()
                    doLast { println("CLIENT EXECUTED") }
                }
            }
            val selectedClients = if (providers.gradleProperty("fixture.omitProduction").isPresent) clients.take(1) else clients
            val fullSuite = tasks.register("fullSuite") { dependsOn(selectedClients) }
            tasks.register("check") { dependsOn(fullSuite) }
            """.trimIndent(),
        )
    }

    /**
     * Runs the production controller against this fixture with admission evaluated on every invocation.
     */
    private fun runner(
        backend: String,
        vararg arguments: String,
    ): GradleRunner {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("gradle/libs.versions.toml")) }
        return GradleRunner.create().withProjectDir(directory.toFile()).withArguments(
            listOf(
                "$owner:check",
                "-I",
                repository.resolve("gradle/verify-selected-clients.init.gradle.kts").toString(),
                "-Pstrata.minecraftVersions=26.3",
                "-Pstrata.canvas.backend=$backend",
                "--no-configuration-cache",
                "--offline",
                "--max-workers=1",
                "--stacktrace",
            ) + arguments,
        )
    }
}
