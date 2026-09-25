package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Creates an independent native GPU completion probe after renderer initialization or callback work.
 *
 * The client-thread caller owns the fence; the manager's immediately following capture fence controls submission.
 * Queries use a zero timeout, perform no readback, and propagate native query failures to the loaded runner.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createMinecraftCanvasTestFence(): NativeCanvasFence {
    RenderSystem.assertOnRenderThread()
    val fence = RenderSystem.getDevice().createCommandEncoder().createFence()
    return object : NativeCanvasFence {
        override fun isSignalled(): Boolean = fence.awaitCompletion(0L)

        override fun close() {
            fence.close()
        }
    }
}
