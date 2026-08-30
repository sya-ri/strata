package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Creates a texture wrapper borrowing Canvas color storage and Minecraft's nearest clamp sampler without transferring ownership.
 *
 * The render-thread device owns target attachments through their initialization, capture, and GUI fences and physical destruction acknowledgment.
 * Minecraft owns the shared sampler; closing this wrapper releases neither that sampler nor the borrowed attachments.
 * Reload and unregister leave those resources untouched and the wrapper holds no screen or retained tree.
 * The caller registers the returned wrapper and remains responsible for the target if construction fails.
 *
 * @param target initialized target with a color texture and view, borrowed on its owning render thread.
 * @return an unregistered wrapper whose close never destroys borrowed native storage.
 * @throws IllegalStateException when the target has no color texture or view.
 * @throws Throwable when native sampler acquisition fails; target ownership remains with the caller.
 */
@JvmSynthetic
internal fun createFabricNativeCanvasTexture(target: RenderTarget): AbstractTexture = FabricCanvasBorrowedTexture(target)

private class FabricCanvasBorrowedTexture(
    target: RenderTarget,
) : AbstractTexture() {
    init {
        texture = checkNotNull(target.colorTexture)
        textureView = checkNotNull(target.colorTextureView)
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    }

    override fun close() = Unit
}
