package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * One presentation's externally leased texture contents or custom-renderer invocation.
 *
 * The device owns this lease, calls it only on its render thread, and releases it after a capture fence even when rendering throws.
 * Implementations must keep source contents and native storage stable until close, not merely until render returns.
 */
@InternalStrataRuntimeApi
public interface NativeCanvasCapture : AutoCloseable {
    /**
     * Captures into a borrowed Strata-owned target without reading GPU pixels back to the CPU.
     *
     * @param target exclusively borrowed color/depth target, valid only during this call.
     * @param logicalSize the final positive logical destination extent.
     * @param frameTime the timestamp shared by this actual native presentation.
     * @return an optional immutable straight-ARGB snapshot of exactly this target generation, physical extent, and top-left orientation.
     * @throws Throwable when validation or GPU work fails; the device retains the target and lease until completion is established.
     */
    public fun render(
        target: NativeCanvasTarget,
        logicalSize: IntSize,
        frameTime: FrameTime,
    ): DrawImage?

    /**
     * Releases the external capture lease after GPU capture has completed, independently of later GUI consumption.
     *
     * This owner-thread callback may enqueue native destruction but must not issue new GPU work.
     * Terminal cleanup completes the submitted queue before releasing remaining leases.
     *
     * @throws Throwable when source-lease cleanup fails; remaining cleanup still proceeds.
     */
    override fun close()
}
