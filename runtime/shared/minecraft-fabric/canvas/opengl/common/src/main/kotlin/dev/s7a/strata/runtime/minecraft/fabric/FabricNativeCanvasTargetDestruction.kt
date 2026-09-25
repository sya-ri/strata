package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL13

/**
 * Releases a fenced legacy target without redirecting later GUI work or changing unrelated texture bindings.
 *
 * Vanilla target destruction unconditionally unbinds the current texture and framebuffer, even when another target is active.
 * The render-thread device invokes this only after all target uses complete; caller-owned bindings are restored and retiring names become zero.
 * No GPU wait, allocation, or screen reference is introduced, and partial native cleanup failures propagate with state restoration suppressed when necessary.
 *
 * @param target device-owned complete or partial framebuffer whose native attachments are ready for destruction.
 * @throws Throwable when native release or state restoration fails; the device keeps the unacknowledged lifetime permit.
 */
@JvmSynthetic
internal fun destroyCanvasRenderTarget(target: RenderTarget) {
    RenderSystem.assertOnRenderThread()
    FabricNativeCanvasGlState(target.frameBufferId, target.colorTextureId, target.depthTextureId).use {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0)
        target.destroyBuffers()
    }
}
