package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

/**
 * Saves the OpenGL bindings and fixed state touched by Canvas allocation, clearing, or sampling.
 *
 * Construction and close run in the same render-thread context and allocate only detached scalar state.
 * Raw operations are paired so Minecraft's untouched state cache remains consistent with the restored native state.
 * Custom renderers remain responsible for restoring additional state they change.
 * Retiring native names are restored as zero rather than rebound after deletion, while unrelated bindings remain unchanged.
 *
 * @param discardedFramebuffer framebuffer being destroyed within this scope, or a negative value when none is retired.
 * @param discardedColor color texture being destroyed within this scope, or a negative value when none is retired.
 * @param discardedDepth depth texture being destroyed within this scope, or a negative value when none is retired.
 */
internal class FabricNativeCanvasGlState(
    discardedFramebuffer: Int = -1,
    discardedColor: Int = -1,
    discardedDepth: Int = -1,
) : AutoCloseable {
    private val drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING).let { if (it == discardedFramebuffer) 0 else it }
    private val readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING).let { if (it == discardedFramebuffer) 0 else it }
    private val viewport = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }
    private val scissorBox = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, it) }
    private val colorMask = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, it) }
    private val clearColor = FloatArray(4).also { GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, it) }
    private val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
    private val clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE)
    private val program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
    private val vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
    private val activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
    private val texture = textureBinding().let { if (it == discardedColor || it == discardedDepth) 0 else it }
    private val blend = GL11.glIsEnabled(GL11.GL_BLEND)
    private val depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
    private val cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
    private val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)

    @JvmSynthetic
    override fun close() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GL11.glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0)
        RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0)
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        GL11.glDepthMask(depthMask)
        RenderSystem.depthMask(depthMask)
        GL11.glClearDepth(clearDepth)
        GL20.glUseProgram(program)
        GL30.glBindVertexArray(vertexArray)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        RenderSystem.activeTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        RenderSystem.bindTexture(texture)
        GL13.glActiveTexture(activeTexture)
        RenderSystem.activeTexture(activeTexture)
        restoreCapability(GL11.GL_BLEND, blend)
        restoreCapability(GL11.GL_DEPTH_TEST, depth)
        restoreCapability(GL11.GL_CULL_FACE, cull)
        restoreCapability(GL11.GL_SCISSOR_TEST, scissor)
        if (blend) RenderSystem.enableBlend() else RenderSystem.disableBlend()
        if (depth) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
        if (cull) RenderSystem.enableCull() else RenderSystem.disableCull()
        if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]) else RenderSystem.disableScissor()
    }

    private fun textureBinding(): Int {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        return try {
            GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        } finally {
            GL13.glActiveTexture(activeTexture)
        }
    }

    private fun restoreCapability(
        capability: Int,
        enabled: Boolean,
    ) {
        if (enabled) GL11.glEnable(capability) else GL11.glDisable(capability)
    }
}
