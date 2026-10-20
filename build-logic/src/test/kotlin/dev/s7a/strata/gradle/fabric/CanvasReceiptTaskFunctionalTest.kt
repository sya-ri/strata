package dev.s7a.strata.gradle.fabric

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Exercises receipt cleanup, invocation identity, and completion failure through actual Gradle task actions.
 */
internal class CanvasReceiptTaskFunctionalTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `each execution deletes its old receipt and rejects another invocations proof`() {
        prepareFixture()
        val first = runner().build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":client")?.outcome)
        val firstId = receiptRunId()
        val second = runner().build()
        assertEquals(TaskOutcome.SUCCESS, second.task(":client")?.outcome)
        assertNotEquals(firstId, receiptRunId())
        val stale = runner("-Pfixture.staleRunId=$firstId").buildAndFail()
        assertEquals(TaskOutcome.FAILED, stale.task(":client")?.outcome)
        assertTrue(stale.output.contains("The terminal Canvas receipt belongs to another invocation."), stale.output)
    }

    @Test
    fun `successful task action without a receipt fails verification`() {
        prepareFixture()
        val result = runner("-Pfixture.omitReceipt=true").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":client")?.outcome)
        assertTrue(result.output.contains("Actual Minecraft shutdown did not produce the native Canvas receipt"), result.output)
    }

    /**
     * Creates a client action that writes controlled evidence after asserting pre-launch cleanup.
     */
    private fun prepareFixture() {
        Files.writeString(directory.resolve("settings.gradle.kts"), "rootProject.name = \"canvas-receipt\"")
        checkNotNull(javaClass.getResourceAsStream("/canvas-receipt/valid.properties")).use {
            Files.copy(it, directory.resolve("baseline.properties"))
        }
        Files.createDirectories(directory.resolve("build"))
        Files.writeString(directory.resolve("build/receipt.properties"), "previous invocation")
        Files.writeString(
            directory.resolve("build.gradle.kts"),
            """
            import dev.s7a.strata.gradle.fabric.CanvasVerification
            import java.io.File
            import java.util.Properties

            plugins { id("dev.s7a.strata.release") }
            tasks.register("client") {
                val receipt = layout.buildDirectory.file("receipt.properties")
                val baseline = layout.projectDirectory.file("baseline.properties").asFile
                val arguments = mutableMapOf<String, String>()
                val omitted = providers.gradleProperty("fixture.omitReceipt").isPresent
                val staleRunId = providers.gradleProperty("fixture.staleRunId").orNull
                doLast {
                    val file = File(arguments.getValue("strata.canvas.shutdown.receipt"))
                    check(file == receipt.get().asFile) { "Launcher receipt path differs from the owned task output." }
                    check(file.exists().not()) { "Previous receipt survived pre-launch cleanup." }
                    if (omitted.not()) {
                        val proof = Properties().apply { baseline.reader(Charsets.UTF_8).use(::load) }
                        proof.setProperty("runId", staleRunId ?: arguments.getValue("strata.canvas.shutdown.run"))
                        proof.setProperty("minecraftVersion", arguments.getValue("strata.minecraftVersion"))
                        proof.setProperty("suiteScope", arguments.getValue("strata.canvas.scope"))
                        file.writer(Charsets.UTF_8).use { proof.store(it, null) }
                    }
                }
                CanvasVerification.requireTerminalReceipt(this, receipt, "26.3") { key, value -> arguments[key] = value }
            }
            """.trimIndent(),
        )
    }

    /**
     * Reads the last completed receipt's invocation token.
     */
    private fun receiptRunId(): String =
        Properties()
            .apply { Files.newBufferedReader(directory.resolve("build/receipt.properties")).use(::load) }
            .getProperty("runId")

    /**
     * Uses the real build-logic classes and the fixture's isolated Gradle task graph.
     */
    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath().withArguments(
            listOf("client", "--build-cache", "--offline", "--no-configuration-cache", "--max-workers=1", "--stacktrace") + arguments,
        )
}
