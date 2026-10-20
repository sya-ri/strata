package dev.s7a.strata.gradle.fabric

import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.UUID

/**
 * Configures independent native Canvas acceptance runs and requires their fresh shutdown evidence.
 * Each task owns its receipt file; this helper retains no shared client or invocation state.
 */
public object CanvasVerification {
    /**
     * Explicit native device selection, decoded before a client is launched.
     */
    public enum class Backend(
        public val argument: String,
    ) {
        OpenGl("opengl"),
        Vulkan("vulkan"),
    }

    /**
     * Explicit loaded-suite scope; ordinary verification always defaults to the complete shared suite.
     */
    public enum class Scope(
        public val argument: String,
    ) {
        Full("full"),
        CanvasOnly("canvas-only"),
        TerminalOnly("terminal-only"),
    }

    /**
     * Decodes an exact command-line backend selector, rejecting unknown spellings.
     */
    public fun parseBackend(value: String): Backend =
        requireNotNull(Backend.entries.singleOrNull { it.argument == value }) {
            "strata.canvas.backend must be opengl or vulkan."
        }

    /**
     * Decodes an exact command-line suite selector; callers supply the full-suite default.
     */
    public fun parseScope(value: String): Scope =
        requireNotNull(Scope.entries.singleOrNull { it.argument == value }) {
            "strata.canvas.scope must be full, canvas-only, or terminal-only."
        }

    /**
     * Gives one task a fresh invocation token, deletes its previous receipt before execution, and validates shutdown afterward.
     * The caller exclusively owns the receipt and supplies the launcher's JVM-property adapter during configuration.
     * Missing, stale, or incomplete evidence fails independently of the client's exit code; receipt evidence is never cached.
     */
    public fun requireTerminalReceipt(
        task: Task,
        receipt: Provider<RegularFile>,
        minecraftVersion: String,
        suiteScope: Scope = Scope.Full,
        setProperty: (String, String) -> Unit,
    ) {
        with(task) {
            val runId = UUID.randomUUID().toString()
            inputs.property("canvasTerminalReceipt", receipt.map { it.asFile.absolutePath })
            inputs.property("canvasTerminalMinecraftVersion", minecraftVersion)
            inputs.property("canvasTerminalRunId", runId)
            inputs.property("canvasTerminalSuiteScope", suiteScope.argument)
            outputs.file(receipt)
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            setProperty("strata.canvas.shutdown.receipt", receipt.get().asFile.absolutePath)
            setProperty("strata.canvas.shutdown.run", runId)
            setProperty("strata.minecraftVersion", minecraftVersion)
            setProperty("strata.canvas.scope", suiteScope.argument)
            doFirst {
                Files.deleteIfExists(receipt.get().asFile.toPath())
            }
            doLast {
                verifyReceipt(receipt.get().asFile, runId, minecraftVersion, suiteScope)
            }
        }
    }

    /**
     * Requires the configured invocation, suite, and completed ownership lifecycle from an actual shutdown receipt.
     */
    internal fun verifyReceipt(
        file: File,
        runId: String,
        minecraftVersion: String,
        suiteScope: Scope,
    ) {
        check(file.isFile) { "Actual Minecraft shutdown did not produce the native Canvas receipt: $file" }
        val proof = Properties()
        file.reader(Charsets.UTF_8).use(proof::load)
        check(proof.getProperty("runId") == runId) { "The terminal Canvas receipt belongs to another invocation." }
        check(proof.getProperty("minecraftVersion") == minecraftVersion) { "The terminal Canvas receipt has the wrong Minecraft version." }
        check(proof.getProperty("suiteScope") == suiteScope.argument) { "The terminal Canvas receipt belongs to another verification scope." }
        check(proof.getProperty("verifiedChecks").isNullOrBlank().not()) { "The terminal Canvas receipt does not declare its verified checks." }
        check(proof.getProperty("excludedChecks").isNullOrBlank().not()) { "The terminal Canvas receipt does not declare its scope exclusions." }
        val menuBackgroundBlurriness =
            requireNotNull(proof.getProperty("menuBackgroundBlurriness")?.toIntOrNull()) {
                "The terminal Canvas receipt does not record the actual menu background blur option."
            }
        check(menuBackgroundBlurriness in 0..10) { "The terminal Canvas receipt has an invalid menu background blur option." }
        check(proof.getProperty("originalCloseReturned")?.toBooleanStrictOrNull() == true) { "The terminal Canvas proof did not observe successful original close." }
        val backend = proof.getProperty("backend").orEmpty()
        check(Backend.entries.any { it.name.equals(backend, ignoreCase = true) }) { "The terminal Canvas receipt has no recognized native backend." }
        listOf("queuedElementsBefore", "portableSetsBefore").forEach { key ->
            check(0 < requireNotNull(proof.getProperty(key)?.toIntOrNull())) { "The terminal Canvas receipt did not prove pending native GUI work: $key" }
        }
        listOf("queuedElementsAdded", "queuedBlitsAdded").forEach { key ->
            check(3 <= requireNotNull(proof.getProperty(key)?.toIntOrNull())) { "The terminal Canvas receipt did not prove the mixed portable/native queue: $key" }
        }
        listOf("targetsBefore", "renderersOpened", "renderCalls", "renderersClosed").forEach { key ->
            check(proof.getProperty(key)?.toIntOrNull() == 1) { "The terminal Canvas receipt did not prove one complete renderer lifetime: $key" }
        }
        listOf("queuedElementsAfter", "targetsAfter", "portableSetsAfter").forEach { key ->
            check(proof.getProperty(key)?.toIntOrNull() == 0) { "The terminal Canvas receipt retained native ownership after shutdown: $key" }
        }
    }
}
