package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.buffers.GpuFence
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns a Minecraft GPU fence and exposes nonblocking render-thread completion checks.
 *
 * The device releases this wrapper exactly once after it no longer protects allocation initialization, capture, or GUI consumption.
 * Native polling and close failures propagate to the device's quarantine and cleanup handling.
 *
 * @param fence native fence transferred after its owner records it behind the protected work; the backend host owns any required submission.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricNativeCanvasGpuFence(
    private val fence: GpuFence,
) : NativeCanvasFence {
    @JvmSynthetic
    override fun isSignalled(): Boolean {
        RenderSystem.assertOnRenderThread()
        return fence.awaitCompletion(0L)
    }

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        fence.close()
    }
}
