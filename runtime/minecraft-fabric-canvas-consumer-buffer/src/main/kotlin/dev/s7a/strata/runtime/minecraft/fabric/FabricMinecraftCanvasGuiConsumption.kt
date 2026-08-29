@file:JvmName("FabricMinecraftCanvasGuiConsumption")
@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas.FabricMinecraftCanvasGameRendererAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer

/**
 * Ends screen extraction without treating its deferred GUI commands as consumed.
 *
 * The common screen finally block calls this on the render thread for both successful and failed extraction.
 * All queued targets remain held by the device until the actual GuiRenderer wrapper submits or quarantines the batch.
 * This family spans the GuiGraphics to GuiGraphicsExtractor rename, so the unused context is deliberately borrowed opaquely.
 *
 * @param graphics borrowed extraction context; neither retained nor invoked at this boundary.
 * @param failure original extraction failure, left primary and handled by the screen transaction.
 * @throws IllegalStateException if screen extraction ended off the render thread.
 */
@Suppress("UNUSED_PARAMETER")
@JvmSynthetic
internal fun finishCanvasGui(
    graphics: Any,
    failure: Throwable? = null,
) {
    RenderSystem.assertOnRenderThread()
}

/**
 * Discards extracted and prepared GUI references without submitting draws or releasing GPU buffers.
 *
 * The client and renderer are borrowed on the render thread only while this call runs.
 * A missing renderer during partial client startup has no GUI queue to discard.
 * A failure prevents terminal Canvas target release because a safe queue boundary has not been established.
 *
 * @param client native owner of the GUI consumer; no source or input callback is invoked here.
 * @throws Throwable after independent queue cleanup has been attempted if discard fails.
 */
internal fun discardCanvasGui(client: Minecraft) {
    RenderSystem.assertOnRenderThread()
    val renderer: GameRenderer? = client.gameRenderer
    if (renderer == null) return
    val gui = (renderer as FabricMinecraftCanvasGameRendererAccess).strataCanvasGuiRenderer()
    (gui as FabricMinecraftCanvasGuiDiscard).strataDiscardCanvasGui()
}
