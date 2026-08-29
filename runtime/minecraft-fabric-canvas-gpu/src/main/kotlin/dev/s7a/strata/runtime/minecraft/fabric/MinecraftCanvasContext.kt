package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime

/**
 * Borrows an offscreen target and command encoder for one render-thread Canvas callback.
 *
 * The target is exclusively owned by Strata and has been cleared to transparent.
 * Renderers encode all target work through [encoder], close every render pass before returning, and never submit or retain this encoder themselves.
 * The adapter completes the family's immediate-issue or explicit-submit boundary even when the callback fails, then protects resources with a capture fence.
 * Output is ordinary RGBA8 straight alpha with texture row zero representing the top logical image row.
 * Do not retain this context or its native objects, resize or destroy the target, or draw to the current GUI target.
 * Physical target axes never exceed the adapter's 32,768-pixel arithmetic bound; the active device may impose a lower limit.
 *
 * @property logicalSize immutable positive logical destination extent from the final layout.
 * @property physicalSize immutable native target extent after applying the presentation's GUI scale.
 * @property frameTime immutable timestamp shared by this native presentation, independent of extra retained host frames.
 */
public class MinecraftCanvasContext private constructor(
    private val borrowedTarget: RenderTarget,
    private val borrowedEncoder: CommandEncoder,
    public val logicalSize: IntSize,
    public val physicalSize: IntSize,
    public val frameTime: FrameTime,
) {
    private var active: Boolean = true

    /**
     * The exclusively borrowed color target and optional depth attachment.
     *
     * Access is confined to the render-thread callback; ownership remains with the device through GPU completion.
     * Reading this property does not permit retaining, resizing, or closing the target.
     *
     * @throws IllegalStateException off the render thread or after the callback returns or fails.
     */
    public val target: RenderTarget
        get() {
            RenderSystem.assertOnRenderThread()
            check(active) { "A Canvas target is borrowed only during its renderer callback." }
            return borrowedTarget
        }

    /**
     * The callback encoder whose command completion boundary and fence are managed by the adapter.
     *
     * Use it only on the render thread, close every opened render pass before returning, and do not retain or submit the encoder.
     * Whether commands are issued immediately or need explicit submission remains a version-adapter responsibility.
     *
     * @throws IllegalStateException off the render thread or after the callback returns or fails.
     */
    public val encoder: CommandEncoder
        get() {
            RenderSystem.assertOnRenderThread()
            check(active) { "A Canvas encoder is borrowed only during its renderer callback." }
            return borrowedEncoder
        }

    /**
     * Ends native access after the callback without closing the device-owned target or adapter-owned encoder.
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
     * Provides the owning version adapter's render-thread context construction boundary.
     *
     * The factory retains no context or native resource; each caller must expire its borrowed context after one callback.
     */
    internal companion object {
        /**
         * Borrows a device-owned target and encoder for one render-thread callback without transferring ownership.
         *
         * @param target exclusively borrowed target, retained by the device through its completion fence.
         * @param encoder callback encoder whose submission remains owned by the adapter.
         * @param logicalSize final positive destination extent.
         * @param physicalSize allocated native target extent.
         * @param frameTime timestamp for this native presentation.
         * @return a context that the adapter must expire in its callback finally block.
         * @throws IllegalStateException when created off the render thread.
         */
        @JvmSynthetic
        internal fun create(
            target: RenderTarget,
            encoder: CommandEncoder,
            logicalSize: IntSize,
            physicalSize: IntSize,
            frameTime: FrameTime,
        ): MinecraftCanvasContext {
            RenderSystem.assertOnRenderThread()
            return MinecraftCanvasContext(target, encoder, logicalSize, physicalSize, frameTime)
        }
    }
}
