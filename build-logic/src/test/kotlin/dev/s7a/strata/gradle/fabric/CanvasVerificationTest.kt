package dev.s7a.strata.gradle.fabric

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Properties

/**
 * Checks the shared native receipt contract without loading a game or opening a render device.
 */
internal class CanvasVerificationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `command selectors retain exact spellings`() {
        mapOf("opengl" to CanvasVerification.Backend.OpenGl, "vulkan" to CanvasVerification.Backend.Vulkan).forEach { (argument, backend) ->
            assertEquals(backend, CanvasVerification.parseBackend(argument))
            assertEquals(argument, backend.argument)
        }
        mapOf(
            "full" to CanvasVerification.Scope.Full,
            "canvas-only" to CanvasVerification.Scope.CanvasOnly,
            "terminal-only" to CanvasVerification.Scope.TerminalOnly,
        ).forEach { (argument, scope) ->
            assertEquals(scope, CanvasVerification.parseScope(argument))
            assertEquals(argument, scope.argument)
        }
        listOf("", "OPENGL", "default").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CanvasVerification.parseBackend(value) }
        }
        listOf("", "FULL", "partial").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CanvasVerification.parseScope(value) }
        }
    }

    @Test
    fun `shared configuration keeps each task invocation and receipt independent`() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val tokens =
            CanvasVerification.Scope.entries.map { scope ->
                val task = project.tasks.register("client${scope.name}").get()
                val receipt = project.layout.buildDirectory.file("${scope.argument}/receipt.properties")
                val arguments = mutableMapOf<String, String>()
                CanvasVerification.requireTerminalReceipt(task, receipt, "26.3", scope) { key, value -> arguments[key] = value }
                assertEquals(receipt.get().asFile.absolutePath, arguments["strata.canvas.shutdown.receipt"])
                assertEquals("26.3", arguments["strata.minecraftVersion"])
                assertEquals(scope.argument, arguments["strata.canvas.scope"])
                arguments.getValue("strata.canvas.shutdown.run")
            }
        assertEquals(CanvasVerification.Scope.entries.size, tokens.toSet().size)
    }

    @Test
    fun `valid receipts accept each supported scope and native backend`() {
        CanvasVerification.Scope.entries.forEach { scope ->
            CanvasVerification.Backend.entries.forEach { backend ->
                val proof = validProof()
                proof.setProperty("suiteScope", scope.argument)
                proof.setProperty("backend", backend.name.lowercase())
                verify(proof, scope)
            }
        }
    }

    @Test
    fun `every receipt field is required`() {
        validProof().stringPropertyNames().forEach { key ->
            val proof = validProof().apply { remove(key) }
            assertThrows(RuntimeException::class.java, { verify(proof) }, key)
        }
    }

    @Test
    fun `stale partial malformed and retained lifecycle evidence is rejected`() {
        val invalid =
            mapOf(
                "runId" to listOf("another-invocation"),
                "minecraftVersion" to listOf("26.2"),
                "suiteScope" to listOf("canvas-only"),
                "verifiedChecks" to listOf(""),
                "excludedChecks" to listOf(""),
                "menuBackgroundBlurriness" to listOf("-1", "11", "invalid"),
                "originalCloseReturned" to listOf("false", "TRUE"),
                "backend" to listOf("unknown"),
                "queuedElementsBefore" to listOf("0", "invalid"),
                "portableSetsBefore" to listOf("0", "invalid"),
                "queuedElementsAdded" to listOf("2", "invalid"),
                "queuedBlitsAdded" to listOf("2", "invalid"),
                "targetsBefore" to listOf("0", "2"),
                "renderersOpened" to listOf("0", "2"),
                "renderCalls" to listOf("0", "2"),
                "renderersClosed" to listOf("0", "2"),
                "queuedElementsAfter" to listOf("1", "invalid"),
                "targetsAfter" to listOf("1", "invalid"),
                "portableSetsAfter" to listOf("1", "invalid"),
            )
        invalid.forEach { (key, values) ->
            values.forEach { value ->
                val proof = validProof().apply { setProperty(key, value) }
                assertThrows(RuntimeException::class.java, { verify(proof) }, "$key=$value")
            }
        }
    }

    @Test
    fun `a missing receipt fails independently of successful process exit`() {
        assertThrows(IllegalStateException::class.java) {
            CanvasVerification.verifyReceipt(directory.resolve("missing.properties").toFile(), "valid-invocation", "26.3", CanvasVerification.Scope.Full)
        }
    }

    /**
     * Loads a new mutable copy of the canonical complete shutdown fixture.
     */
    private fun validProof(): Properties =
        Properties().apply {
            checkNotNull(CanvasVerificationTest::class.java.getResourceAsStream("/canvas-receipt/valid.properties")).use(::load)
        }

    /**
     * Writes an owned UTF-8 receipt and applies the same validator used by the Gradle completion action.
     */
    private fun verify(
        proof: Properties,
        scope: CanvasVerification.Scope = CanvasVerification.Scope.Full,
    ) {
        val receipt = directory.resolve("receipt.properties").toFile()
        receipt.writer(Charsets.UTF_8).use { proof.store(it, null) }
        CanvasVerification.verifyReceipt(receipt, "valid-invocation", "26.3", scope)
    }
}
