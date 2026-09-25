package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasCapture
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasProducer
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Lazily owns one renderer per attachment resource generation while the device owns every capture and target.
 *
 * Calls run on the render thread; close is deferred by the device until the renderer's last GPU use completes.
 * The factory runs inside the first capture's render operation, after target reservation, so initialization uploads share its completion fence.
 * An attachment removed before its first presentation invokes neither the factory nor renderer cleanup.
 * Rendering exceptions propagate without closing the renderer while its work is still pending.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricNativeCanvasRendererProducer(
    private val factory: () -> MinecraftCanvasRenderer,
) : NativeCanvasProducer {
    private var renderer: MinecraftCanvasRenderer? = null

    @JvmSynthetic
    override fun capture(): NativeCanvasCapture = Capture(this)

    /**
     * Initializes the attachment renderer lazily and performs one device-owned capture on the render thread.
     *
     * The capture borrows its target and timestamp only for this call; factory and renderer failures propagate unchanged.
     * This synthetic seam keeps private producer state inaccessible to generated cross-class access methods.
     *
     * @param target device-owned target allocated by this version adapter and protected by its reserved lifetime permit.
     * @param logicalSize final positive logical destination extent.
     * @param frameTime immutable timestamp for this actual presentation.
     * @return an optional immutable snapshot of the resulting target's physical pixels in top-left orientation.
     * @throws Throwable when renderer acquisition or drawing fails; the device fences any issued capture work before cleanup.
     */
    @JvmSynthetic
    internal fun renderCaptured(
        target: NativeCanvasTarget,
        logicalSize: IntSize,
        frameTime: FrameTime,
    ): DrawImage? {
        val current = renderer ?: factory().also { renderer = it }
        return FabricNativeCanvasDriver.render(target as FabricNativeCanvasTarget, logicalSize, frameTime, current)
    }

    @JvmSynthetic
    override fun close() {
        val previous = renderer
        renderer = null
        previous?.close()
    }

    private class Capture(
        private val producer: FabricNativeCanvasRendererProducer,
    ) : NativeCanvasCapture {
        override fun render(
            target: NativeCanvasTarget,
            logicalSize: IntSize,
            frameTime: FrameTime,
        ): DrawImage? = producer.renderCaptured(target, logicalSize, frameTime)

        override fun close() = Unit
    }
}
