package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.GuiGraphics

/**
 * Submits one prepared native target through the version's ordered whole-texture GUI path.
 *
 * The graphics context is borrowed on the render thread and never retained; its current clip and transform remain authoritative.
 * The caller queues target use before this call and fences it only after actual GUI consumption, including failures.
 *
 * @param graphics current borrowed native GUI context.
 * @param target prepared device-owned target whose source has already been captured.
 * @param bounds final logical Canvas destination.
 * @throws Throwable when native submission fails; the caller preserves the target until the consumption boundary.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun FabricNativeCanvasDriver.draw(
    graphics: GuiGraphics,
    target: NativeCanvasTarget,
    bounds: IntRect,
) {
    RenderSystem.assertOnRenderThread()
    val native = target as FabricNativeCanvasTarget
    FabricMinecraftTextureBlitter.blit(graphics, native.location, bounds.left, bounds.top, bounds.width, bounds.height)
}
