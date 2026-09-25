package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasAllocationFailure
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one offscreen target and a texture-manager entry that borrows its color attachment.
 *
 * Only the render-thread native device may close this object, after initialization, capture, and GUI-consumption fences complete.
 * The registered texture never owns attachments, so resource reload cannot prematurely destroy queued Canvas pixels.
 * Close attempts unregistering and native release even when one operation throws and preserves the primary failure.
 * Deferred backends retain every owned attachment until [isDestroyed] acknowledges physical destruction.
 *
 * @property renderTarget native target owned by this wrapper; presentation may only borrow it on the render thread before close.
 * @property location immutable texture-manager identifier whose registered texture borrows, rather than owns, the color attachment.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal class FabricNativeCanvasTarget private constructor(
    @get:JvmSynthetic
    internal val renderTarget: RenderTarget,
    @get:JvmSynthetic
    internal val location: MinecraftResourceLocation,
) : NativeCanvasTarget {
    @get:JvmSynthetic
    override val size: IntSize = IntSize(renderTarget.width, renderTarget.height)
    private val destruction: FabricNativeCanvasDestruction = trackCanvasDestruction(renderTarget)
    private var unregistered: Boolean = false
    private var destroyed: Boolean = false

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (unregistered && destroyed) return
        var failure: Throwable? = null
        try {
            if (unregistered.not()) {
                Minecraft.getInstance().textureManager.release(location)
                unregistered = true
            }
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            if (destroyed.not()) {
                destroyCanvasRenderTarget(renderTarget)
                destroyed = true
            }
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
        }
        failure?.let { throw it }
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        check(unregistered && destroyed) { "Canvas destruction is queried only after successful close." }
        return destruction.isDestroyed()
    }

    /**
     * Registers render-thread targets only after the caller has reserved their complete lifetime permits.
     *
     * Construction transfers native ownership to a returned target or to an allocation-failure carrier, never to a retained frame.
     * This factory retains only its process-local identifier counter and no native resource.
     */
    internal companion object {
        /**
         * The process-local counter used to allocate distinct registered Canvas texture names.
         *
         * Only render-thread target registration advances it; the counter owns no native resource and needs no terminal release.
         * Existing target names do not change when the counter advances.
         */
        @JvmField
        @field:JvmSynthetic
        internal val sequence = AtomicLong()

        /**
         * Takes ownership of a newly allocated render target and registers a borrowed texture view.
         *
         * Registration failure transfers the incomplete allocation without requesting native release before its initialization fence exists.
         * The device quarantines it through terminal GPU completion and then unregisters and destroys every owned attachment.
         *
         * @param renderTarget newly allocated native target, transferred on entry.
         * @return a device-owned wrapper with one registered texture identifier.
         * @throws IllegalStateException when called off the render thread.
         * @throws NativeCanvasAllocationFailure on registration failure, transferring the target and its reserved permit to the device.
         */
        @JvmSynthetic
        internal fun create(renderTarget: RenderTarget): FabricNativeCanvasTarget {
            RenderSystem.assertOnRenderThread()
            val location = minecraftResourceLocation("strata", "runtime/canvas/${sequence.getAndIncrement().toULong()}")
            val target = FabricNativeCanvasTarget(renderTarget, location)
            try {
                Minecraft.getInstance().textureManager.register(location, createFabricNativeCanvasTexture(renderTarget))
            } catch (failure: Throwable) {
                throw NativeCanvasAllocationFailure(target, failure)
            }
            return target
        }
    }
}
