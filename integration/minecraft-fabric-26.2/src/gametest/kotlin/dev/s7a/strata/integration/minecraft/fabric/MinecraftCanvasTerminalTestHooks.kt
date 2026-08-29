package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Arms one 26.2-only proof around the actual terminal Minecraft close operation.
 *
 * All state belongs to the client render thread and is removed before any native or user callback.
 * Before close, the observer queues a real Canvas and borrows Minecraft's GUI state until the synchronous close returns.
 * After close, the returned verification callback uses only scalar accounting, cleared GUI-state iteration, and filesystem writes.
 * No runtime artifact includes this hook, and an unarmed or unsuccessful close cannot write a success receipt.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object MinecraftCanvasTerminalTestHooks {
    private var armed: Configuration? = null

    /**
     * Retains only the current Gradle invocation's immutable receipt configuration after its selected suite succeeds.
     *
     * This client-thread operation leaves the framework's final screen and world checks intact.
     * It records the actual menu blur option as part of the test environment without changing it.
     * Missing properties, a relative receipt path, duplicate arming, or an already terminal client fail immediately.
     *
     * @param scope the exact completed suite, recorded with its included checks and exclusions after actual shutdown succeeds.
     */
    internal fun arm(scope: MinecraftCanvasSuiteScope) {
        RenderSystem.assertOnRenderThread()
        FabricMinecraftCanvasHooks.requireRunning()
        check(armed == null) { "The terminal Canvas proof is already armed." }
        val receipt = Path.of(requiredProperty("strata.canvas.shutdown.receipt"))
        require(receipt.isAbsolute) { "The terminal Canvas receipt path must be absolute." }
        val menuBackgroundBlurriness = Minecraft.getInstance().options.getMenuBackgroundBlurriness()
        armed =
            Configuration(
                receipt = receipt,
                runId = requiredProperty("strata.canvas.shutdown.run"),
                minecraftVersion = requiredProperty("strata.minecraftVersion"),
                expectedBackend = System.getProperty("strata.canvas.expectedBackend")?.let(MinecraftCanvasTestBackend::parse),
                scope = scope,
                menuBackgroundBlurriness = menuBackgroundBlurriness,
            )
    }

    /**
     * Takes the armed proof before production shutdown starts and extracts native Canvas work without consuming it.
     * The recorded blur option must remain unchanged through the framework's final window-size restoration.
     *
     * @param client actual Minecraft instance entering its terminal close on the render thread.
     * @return a callback for successful original close completion, or null when the shared suite did not arm a proof.
     * @throws Throwable when the test environment, wrapper order, native preparation, or queued-lifetime assertions fail; the caller must still invoke original close.
     */
    @JvmStatic
    public fun beforeClose(client: Minecraft): Runnable? {
        val configuration = armed ?: return null
        armed = null
        FabricMinecraftCanvasHooks.requireRunning()
        check(client.options.getMenuBackgroundBlurriness() == configuration.menuBackgroundBlurriness) {
            "The menu background blur option changed before terminal Canvas verification."
        }
        val fixture = MinecraftCanvasTerminalFixture()
        fixture.enqueue(client, configuration.expectedBackend)
        return Runnable {
            writeReceipt(configuration, fixture.verifyReleased())
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)?.takeIf(String::isNotBlank)) {
            "The terminal Canvas proof requires system property $name."
        }

    private fun writeReceipt(
        configuration: Configuration,
        evidence: Map<String, String>,
    ) {
        val properties = Properties()
        properties.setProperty("runId", configuration.runId)
        properties.setProperty("minecraftVersion", configuration.minecraftVersion)
        properties.setProperty("suiteScope", configuration.scope.argument)
        properties.setProperty("verifiedChecks", configuration.scope.verifiedChecks)
        properties.setProperty("excludedChecks", configuration.scope.excludedChecks)
        properties.setProperty("menuBackgroundBlurriness", configuration.menuBackgroundBlurriness.toString())
        properties.setProperty("originalCloseReturned", true.toString())
        evidence.forEach { (key, value) -> properties.setProperty(key, value) }
        Files.createDirectories(checkNotNull(configuration.receipt.parent))
        Files.newBufferedWriter(configuration.receipt, StandardCharsets.UTF_8).use { writer ->
            properties.store(writer, "Actual Minecraft close with unconsumed native Canvas work")
        }
    }

    private data class Configuration(
        val receipt: Path,
        val runId: String,
        val minecraftVersion: String,
        val expectedBackend: MinecraftCanvasTestBackend?,
        val scope: MinecraftCanvasSuiteScope,
        val menuBackgroundBlurriness: Int,
    )
}
