package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Runs the complete Canvas-specific loaded acceptance independently of unrelated reference-screen parity.
 *
 * The Fabric runner owns a fresh contained output directory and one temporary integrated world.
 * Native work stays on the client thread; the inventory helper seeds and restores the real server-owned slot.
 * This runner cannot mark shutdown complete: the wrapper must subsequently arm and verify the actual close observer.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasBackendGameTest {
    private val viewport = IntSize(640, 480)

    /**
     * Executes existing native GPU, lifetime, capture, input, failure, and Slot-order cases without substituting an oracle.
     *
     * @param context Fabric test-thread coordinator; no native context escapes a scheduled client callback.
     * @throws Throwable on scene, resource, world, or filesystem failure; world cleanup suppresses onto an existing primary failure.
     */
    internal fun run(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        resizeMinecraftCanvasTestWindow(context, viewport)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.options.forceUnicodeFont().set(false)
                minecraft.options.showAutosaveIndicator().set(false)
                minecraft.resizeGui()
            },
        )
        context.input.setCursorPos((viewport.width - 1).toDouble(), (viewport.height - 1).toDouble())
        val output = Path.of(requireNotNull(System.getProperty("strata.minecraftParityOutput")))
        require(output.isAbsolute) { "Canvas backend evidence requires an absolute contained output directory." }
        Files.createDirectories(output)
        val profile =
            context.computeOnClient(
                FailableFunction<Minecraft, MinecraftUiProfile, RuntimeException> { extractMinecraftUiProfile() },
            )
        runMinecraftCanvasTest(context, profile, output)
        context.worldBuilder().setUseConsistentSettings(true).create().use {
            InventorySlotSynchronizationGameTest.runCanvasOrdering(context, profile, output)
        }
        context.waitFor(
            Predicate<Minecraft> {
                NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0
            },
        )
    }
}
