package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the complete Canvas-specific loaded acceptance independently of unrelated reference-screen parity.
 *
 * The standalone runner owns a fresh contained output directory and one temporary integrated world.
 * Native work stays on the client thread; the inventory helper seeds and restores the real server-owned slot.
 * This runner cannot mark shutdown complete: the wrapper must subsequently arm and verify the actual close observer.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasBackendGameTest {
    private val viewport = IntSize(640, 480)

    /**
     * Executes existing native GPU, lifetime, capture, input, failure, and Slot-order cases without substituting an oracle.
     *
     * @param context Loaded-client test-thread coordinator; no native context escapes a scheduled client callback.
     * @throws Throwable on scene, resource, world, or filesystem failure; world cleanup suppresses onto an existing primary failure.
     */
    internal fun run(context: MinecraftLoadedTestContext) {
        context.restoreDefaultGameOptions()
        resizeMinecraftTestWindow(context, viewport)
        context.runOnClient { minecraft ->
            minecraft.options.guiScale().set(1)
            minecraft.options.forceUnicodeFont().set(false)
            minecraft.options.showAutosaveIndicator().set(false)
            minecraft.resizeGui()
        }
        context.movePointerRaw((viewport.width - 1).toDouble(), (viewport.height - 1).toDouble())
        val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
        require(output.isAbsolute) { "Canvas backend evidence requires an absolute contained output directory." }
        Files.createDirectories(output)
        val profile =
            context.computeOnClient { extractMinecraftUiProfile() }
        runMinecraftCanvasTest(context, profile, output)
        MinecraftOrderedCompositionNativeProbe.run(context, profile, output.resolve("ordered-composition"))
        context.createSingleplayerWorld().use {
            InventorySlotSynchronizationGameTest.runCanvasOrdering(context, profile, output)
        }
        context.waitFor {
            NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0
        }
    }
}
