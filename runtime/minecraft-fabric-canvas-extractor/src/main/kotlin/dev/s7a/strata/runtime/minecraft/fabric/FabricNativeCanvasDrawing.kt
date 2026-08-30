package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines

/**
 * Extracts one prepared Canvas target into the current GUI command queue without executing a producer.
 *
 * Calls borrow the render-thread extractor and preserve its current clip, matrix, and stratum order.
 * The caller records queued target use before this call and holds it through the GUI-renderer's consumption fence.
 *
 * @param graphics current GUI extraction context, never retained by Canvas.
 * @param target already captured device-owned target with nearest sampling and top-left texel orientation.
 * @param bounds final logical destination; physical target resolution affects sampling, not layout.
 * @throws Throwable when extraction fails; queued resource lifetime remains the caller's responsibility.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun FabricNativeCanvasDriver.draw(
    graphics: GuiGraphicsExtractor,
    target: NativeCanvasTarget,
    bounds: IntRect,
) {
    RenderSystem.assertOnRenderThread()
    val native = target as FabricNativeCanvasTarget
    graphics.blit(
        RenderPipelines.GUI_TEXTURED,
        native.location,
        bounds.left,
        bounds.top,
        0f,
        0f,
        bounds.width,
        bounds.height,
        bounds.width,
        bounds.height,
    )
}
