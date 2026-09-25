package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasAllocationFailure
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Retains a partially allocated target until the device establishes completion of its native initialization work.
 *
 * The render-thread device keeps the original lifetime permit until a later close requests release and [isDestroyed] acknowledges every attachment's physical destruction.
 * No texture identifier is registered and this object must never be presented or rendered into.
 * Close is retryable after failure and becomes idempotent after the native release request succeeds.
 * The physical-destruction probe retains all attachment references even when the render target clears its own fields.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricNativeCanvasPartialTarget private constructor(
    private val renderTarget: RenderTarget,
    @get:JvmSynthetic
    override val size: IntSize,
) : NativeCanvasTarget {
    private val destruction: FabricNativeCanvasDestruction = trackCanvasDestruction(renderTarget)
    private var destroyed: Boolean = false

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (destroyed) return
        destroyCanvasRenderTarget(renderTarget)
        destroyed = true
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        check(destroyed) { "Canvas destruction is queried only after successful close." }
        return destruction.isDestroyed()
    }

    /**
     * Transfers incomplete, unregistered native attachments to the device's quarantined lifetime records.
     *
     * The render-thread caller must already own a reserved permit; the factory returns ownership only through a failure carrier.
     * No release is attempted before the device can protect any issued initialization work with its own fence.
     */
    internal companion object {
        /**
         * Transfers partial allocation ownership without closing native attachments before an initialization fence exists.
         *
         * The render-thread device keeps the lifetime permit, records the initialization fence through its driver, and waits only at terminal device cleanup.
         * The exact original allocation failure remains the carrier's cause and primary failure.
         *
         * @param renderTarget partially initialized target whose existing attachments transfer to the device.
         * @param size requested positive physical extent, even if allocation did not initialize the target's extent fields.
         * @param failure original allocation failure to preserve through later independent cleanup.
         * @throws NativeCanvasAllocationFailure always, carrying the partial target without releasing its permit.
         */
        @JvmSynthetic
        internal fun fail(
            renderTarget: RenderTarget,
            size: IntSize,
            failure: Throwable,
        ): Nothing {
            val partial = FabricNativeCanvasPartialTarget(renderTarget, size)
            throw NativeCanvasAllocationFailure(partial, failure)
        }
    }
}
