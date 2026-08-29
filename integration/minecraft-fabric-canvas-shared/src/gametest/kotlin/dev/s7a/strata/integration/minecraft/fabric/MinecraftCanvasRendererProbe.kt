package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns an independent GPU resource initialized by a renderer factory and probes its real last-use completion.
 *
 * Construction, callback recording, and close belong to the client render thread.
 * The resource is a real uploaded native texture; its upload is protected by the factory's first capture fence.
 * Later probes follow the renderer's target work without submitting or waiting, so manager-controlled capture submission remains responsible for progress.
 * Close fails before destroying resources if their real native fence has not signalled.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftCanvasRendererProbe private constructor(
    private val resource: MinecraftCanvasTestResources,
    private var fence: NativeCanvasFence,
) : AutoCloseable {
    private var closed = false

    /**
     * Records a native completion probe after this renderer's most recent callback work.
     *
     * The previous probe is superseded by a later fence on the same queue and can be closed without a wait.
     * Native fence failures propagate through the renderer callback, leaving final resource ownership with this probe.
     */
    internal fun recordUse() {
        check(closed.not()) { "A closed native renderer probe cannot record more GPU work." }
        val previous = fence
        fence = createMinecraftCanvasTestFence()
        previous.close()
    }

    // Why: independent fence and resource cleanup must both run after any native failure.
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        check(closed.not()) { "A renderer's native resource probe was closed twice." }
        check(fence.isSignalled()) { "Renderer resources were closed before their real native last-use fence signalled." }
        var failure: Throwable? = null
        try {
            fence.close()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            resource.close()
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) {
                failure = caught
            } else if (primary !== caught) {
                primary.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
        closed = true
    }

    /**
     * Creates the independently uploaded resource only during the native renderer factory's first capture.
     */
    internal companion object {
        // Why: an untransferred native resource must close after any probe failure while preserving the original cause.

        /**
         * Transfers one real native resource and its initialization completion probe on success.
         *
         * The caller already holds a Canvas target permit and will submit the capture even if later rendering fails.
         * Native allocation failures propagate, preserving the primary failure if untransferred resource cleanup also fails.
         */
        @Suppress("TooGenericExceptionCaught")
        internal fun create(): MinecraftCanvasRendererProbe {
            val resource = createMinecraftCanvasTestResources()
            return try {
                MinecraftCanvasRendererProbe(resource, createMinecraftCanvasTestFence())
            } catch (failure: Throwable) {
                try {
                    resource.close()
                } catch (cleanup: Throwable) {
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }
    }
}
