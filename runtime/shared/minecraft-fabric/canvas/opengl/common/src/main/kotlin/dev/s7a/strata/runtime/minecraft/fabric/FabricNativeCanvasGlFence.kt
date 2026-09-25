package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.opengl.GL32

/**
 * Owns one legacy OpenGL sync object issued after capture or actual GUI consumption.
 *
 * Polling is render-thread confined and never waits; failed polls propagate so the device can quarantine protected resources.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricNativeCanvasGlFence(
    private val sync: Long,
) : NativeCanvasFence {
    @JvmSynthetic
    override fun isSignalled(): Boolean {
        RenderSystem.assertOnRenderThread()
        return when (GL32.glClientWaitSync(sync, 0, 0L)) {
            GL32.GL_ALREADY_SIGNALED, GL32.GL_CONDITION_SATISFIED -> true
            GL32.GL_TIMEOUT_EXPIRED -> false
            else -> error("Canvas OpenGL fence polling failed.")
        }
    }

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        GL32.glDeleteSync(sync)
    }
}
