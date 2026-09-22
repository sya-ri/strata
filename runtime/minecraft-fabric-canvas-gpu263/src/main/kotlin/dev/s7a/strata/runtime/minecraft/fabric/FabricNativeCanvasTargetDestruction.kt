package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem

/**
 * Requests release of one complete or partial GPU-abstraction target after all of its submitted uses complete.
 *
 * The render-thread device remains the sole owner until its separate destruction probe acknowledges the native texture and view objects.
 * This family has no legacy framebuffer-unbind side effect; backend state and deferred deletion remain owned by the native device.
 * No wait, allocation, or reference to a screen or runtime frame is introduced.
 *
 * @param target device-owned target whose native resources are eligible for release.
 * @throws Throwable when native release fails; the device retains the target and its lifetime permit for terminal cleanup.
 */
@JvmSynthetic
internal fun destroyCanvasRenderTarget(target: RenderTarget) {
    RenderSystem.assertOnRenderThread()
    target.destroyBuffers()
}
