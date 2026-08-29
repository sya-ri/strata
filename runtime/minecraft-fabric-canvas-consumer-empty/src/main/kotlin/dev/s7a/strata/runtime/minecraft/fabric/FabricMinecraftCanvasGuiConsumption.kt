@file:JvmName("FabricMinecraftCanvasGuiConsumption")
@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas.FabricMinecraftCanvasGameRendererAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Ends GUI extraction while leaving queued Canvas targets owned by the later native consumer.
 *
 * Screen success, removal, or failure cannot signal a GUI fence before GuiRenderer has consumed and encoded its queued work.
 * The actual consumer wrapper independently records a completion fence or quarantines the queued batch.
 *
 * @param graphics borrowed extraction context, never retained or consumed here.
 * @param failure original screen failure, left primary for the screen transaction.
 * @throws IllegalStateException when called off the render thread.
 */
@Suppress("UNUSED_PARAMETER")
@JvmSynthetic
internal fun finishCanvasGui(
    graphics: GuiGraphicsExtractor,
    failure: Throwable? = null,
) {
    RenderSystem.assertOnRenderThread()
}

/**
 * Discards extracted GUI texture references and staged draws without closing in-flight GPU buffers.
 *
 * This synchronous render-thread cleanup works with both native device backends and retains no client state.
 * The fully constructed client and renderer are borrowed only while this call runs.
 * The caller must keep Canvas targets quarantined if any independent cleanup fails.
 *
 * @param client native owner of the current GUI queue, borrowed only for this call.
 * @throws Throwable after queue cleanup has been attempted when discard fails.
 */
internal fun discardCanvasGui(client: Minecraft) {
    RenderSystem.assertOnRenderThread()
    val renderer = client.gameRenderer
    val gui = (renderer as FabricMinecraftCanvasGameRendererAccess).strataCanvasGuiRenderer()
    (gui as FabricMinecraftCanvasGuiDiscard).strataDiscardCanvasGui()
}
