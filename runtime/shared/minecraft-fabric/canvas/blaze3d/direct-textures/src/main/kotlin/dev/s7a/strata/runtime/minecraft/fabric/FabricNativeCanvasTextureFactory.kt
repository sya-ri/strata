package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Registers a borrowed GPU color attachment whose lifetime belongs exclusively to the Canvas device.
 *
 * Called on the render thread; reload and unregister leave target storage untouched and capture no screen state.
 */
@JvmSynthetic
internal fun createFabricNativeCanvasTexture(target: RenderTarget): AbstractTexture = FabricCanvasBorrowedTexture(target)

private class FabricCanvasBorrowedTexture(
    target: RenderTarget,
) : AbstractTexture() {
    init {
        texture = checkNotNull(target.colorTexture)
    }

    override fun close() = Unit
}
