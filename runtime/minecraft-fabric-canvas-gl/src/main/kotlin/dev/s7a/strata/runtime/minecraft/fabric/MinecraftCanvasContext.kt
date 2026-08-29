package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime

/**
 * Borrows an offscreen OpenGL target for one render-thread Canvas callback.
 *
 * The target is exclusively owned by Strata, is already bound with its physical viewport, and is cleared to transparent.
 * Renderers must produce RGBA8 straight-alpha pixels with native texel row zero representing the top logical image row.
 * Native state changed by the renderer must be restored before return; the adapter restores framebuffer and viewport bindings on failure as well.
 * Do not retain this context or its target, destroy its attachments, resize it, or draw to the current GUI framebuffer.
 * Physical target axes never exceed the adapter's 32,768-pixel arithmetic bound; the active device may impose a lower limit.
 *
 * @property logicalSize immutable positive logical destination extent from the final layout.
 * @property physicalSize immutable native target extent after applying the presentation's GUI scale.
 * @property frameTime immutable timestamp shared by this native presentation, independent of extra retained host frames.
 */
public class MinecraftCanvasContext private constructor(
    private val borrowedTarget: RenderTarget,
    public val logicalSize: IntSize,
    public val physicalSize: IntSize,
    public val frameTime: FrameTime,
) {
    private var active: Boolean = true

    /**
     * The borrowed color target with an optional depth attachment.
     *
     * Access is confined to the render-thread callback; ownership remains with the device through GPU completion.
     * Reading this property does not permit retaining, resizing, or closing the target.
     *
     * @throws IllegalStateException off the render thread or after the renderer callback returns or fails.
     */
    public val target: RenderTarget
        get() {
            RenderSystem.assertOnRenderThread()
            check(active) { "A Canvas target is borrowed only during its renderer callback." }
            return borrowedTarget
        }

    /**
     * Ends native access after the callback without releasing the device-owned target or its attachments.
     *
     * The adapter invokes this on the render thread in its callback cleanup path, including after a renderer failure.
     * Repeated calls are harmless; later native getters reject access while copied immutable metadata remains unchanged.
     *
     * @throws IllegalStateException when called off the render thread.
     */
    @JvmSynthetic
    internal fun expire() {
        RenderSystem.assertOnRenderThread()
        active = false
    }

    /**
     * Provides the owning OpenGL adapter's render-thread context construction boundary.
     *
     * The factory retains no context or native resource; each caller must expire its borrowed context after one callback.
     */
    internal companion object {
        /**
         * Borrows a device-owned target for one render-thread callback without transferring native ownership.
         *
         * @param target exclusively borrowed offscreen target, already bound and cleared by the adapter.
         * @param logicalSize final positive logical destination extent.
         * @param physicalSize allocated native target extent.
         * @param frameTime timestamp for this native presentation.
         * @return a context that the adapter expires before returning from the callback.
         * @throws IllegalStateException when created off the render thread.
         */
        @JvmSynthetic
        internal fun create(
            target: RenderTarget,
            logicalSize: IntSize,
            physicalSize: IntSize,
            frameTime: FrameTime,
        ): MinecraftCanvasContext {
            RenderSystem.assertOnRenderThread()
            return MinecraftCanvasContext(target, logicalSize, physicalSize, frameTime)
        }
    }
}
