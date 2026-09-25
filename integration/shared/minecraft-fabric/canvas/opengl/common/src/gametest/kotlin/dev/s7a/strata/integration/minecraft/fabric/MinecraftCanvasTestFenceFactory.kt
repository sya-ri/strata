package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.opengl.GL32

/**
 * Creates an independent real OpenGL completion probe after renderer initialization or callback work.
 *
 * The client-thread caller owns the sync object; the Canvas manager's subsequent capture fence flushes the queue.
 * Queries never wait, close deletes only this probe, and native allocation or query failures propagate to the loaded runner.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createMinecraftCanvasTestFence(): NativeCanvasFence {
    RenderSystem.assertOnRenderThread()
    val sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    check(sync != 0L) { "The loaded native renderer probe could not allocate an OpenGL fence." }
    return object : NativeCanvasFence {
        override fun isSignalled(): Boolean =
            when (GL32.glClientWaitSync(sync, 0, 0L)) {
                GL32.GL_ALREADY_SIGNALED, GL32.GL_CONDITION_SATISFIED -> true
                GL32.GL_TIMEOUT_EXPIRED -> false
                else -> error("The loaded native renderer fence query failed.")
            }

        override fun close() {
            GL32.glDeleteSync(sync)
        }
    }
}
