package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21

/**
 * Borrows tightly packed CPU pixel transfers for the independent loaded OpenGL texture oracle.
 *
 * Minecraft image and font uploads can leave nonzero pixel skips and row lengths behind.
 * Construction saves those render-thread values and pixel-buffer bindings, then selects tightly packed client memory for both upload and explicit test-only readback.
 * Close restores every borrowed value without allocating, waiting, or retaining image storage.
 * The caller must keep this scope on the same active render context and close it after any fixture failure.
 */
internal class MinecraftCanvasGlPixelStorage : AutoCloseable {
    private val rowParameters =
        intArrayOf(
            GL11.GL_UNPACK_ROW_LENGTH,
            GL11.GL_UNPACK_SKIP_ROWS,
            GL11.GL_UNPACK_SKIP_PIXELS,
            GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_PACK_SKIP_ROWS,
            GL11.GL_PACK_SKIP_PIXELS,
        )
    private val previousRows = rowParameters.map(GL11::glGetInteger)
    private val packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
    private val unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
    private val packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
    private val unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)

    init {
        RenderSystem.assertOnRenderThread()
        rowParameters.forEach { GL11.glPixelStorei(it, 0) }
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0)
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        rowParameters.forEachIndexed { index, parameter -> GL11.glPixelStorei(parameter, previousRows[index]) }
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
    }
}
