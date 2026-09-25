package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Creates a texture-manager entry that borrows both color texture and view from a device-owned Canvas target.
 *
 * Render-thread reload and unregister do not close either attachment, so queued GUI work remains valid until its fence.
 */
@JvmSynthetic
internal fun createFabricNativeCanvasTexture(target: RenderTarget): AbstractTexture = FabricCanvasBorrowedTexture(target)

private class FabricCanvasBorrowedTexture(
    target: RenderTarget,
) : AbstractTexture() {
    init {
        texture = checkNotNull(target.colorTexture)
        textureView = checkNotNull(target.colorTextureView)
    }

    override fun close() = Unit
}
