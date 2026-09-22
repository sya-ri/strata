package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.opengl.GlStateManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL32
import org.lwjgl.opengl.GL33
import org.lwjgl.system.MemoryStack

/**
 * Restores OpenGL state touched by an isolated standard GUI render and texture readback.
 * Capture and close are confined to the current render thread and context; the snapshot owns no game or GPU resource.
 * Native state-manager entrypoints restore their caches together with actual GL bindings.
 */
internal class MinecraftNativeFontGlState private constructor() : AutoCloseable {
    private val drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
    private val readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
    private val viewport = integers(GL11.GL_VIEWPORT)
    private val scissor = integers(GL11.GL_SCISSOR_BOX)
    private val scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
    private val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
    private val depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
    private val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
    private val blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
    private val blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
    private val blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
    private val blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
    private val blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
    private val blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)
    private val blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)
    private val cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE)
    private val colorMask = integers(GL11.GL_COLOR_WRITEMASK).foldIndexed(0) { index, mask, component -> mask or (component shl index) }
    private val program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
    private val vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
    private val arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
    private val elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)
    private val uniformBuffer = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING)
    private val pixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
    private val pixelUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)
    private val packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
    private val unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
    private val activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
    private val textures = textureBindings()
    private val uniforms = uniformBindings()

    override fun close() {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GlStateManager._scissorBox(scissor[0], scissor[1], scissor[2], scissor[3])
        if (scissorEnabled) GlStateManager._enableScissorTest() else GlStateManager._disableScissorTest()
        if (depthEnabled) GlStateManager._enableDepthTest() else GlStateManager._disableDepthTest()
        GlStateManager._depthFunc(depthFunction)
        GlStateManager._depthMask(depthMask)
        if (blendEnabled) GlStateManager._enableBlend(0) else GlStateManager._disableBlend(0)
        GlStateManager._blendFuncSeparate(blendSourceRgb, blendDestinationRgb, blendSourceAlpha, blendDestinationAlpha)
        GlStateManager._blendEquationSeparate(blendEquationRgb, blendEquationAlpha)
        if (cullEnabled) GlStateManager._enableCull() else GlStateManager._disableCull()
        GlStateManager._colorMask(0, colorMask)
        GlStateManager._glUseProgram(program)
        GlStateManager._glBindVertexArray(vertexArray)
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer)
        uniforms.forEachIndexed { index, binding ->
            if (binding.buffer == 0 || binding.length == 0L) {
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, binding.buffer)
            } else {
                GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, index, binding.buffer, binding.start, binding.length)
            }
        }
        GlStateManager._glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBuffer)
        GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pixelPackBuffer)
        GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer)
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, packAlignment)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment)
        textures.forEachIndexed { index, binding ->
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + index)
            GlStateManager._bindTexture(binding.texture)
            GL33.glBindSampler(index, binding.sampler)
        }
        GlStateManager._activeTexture(activeTexture)
    }

    private fun textureBindings(): List<TextureBinding> =
        try {
            // Standard GUI TextureSetup exposes three samplers; other units are untouched by this scene.
            List(3) { index ->
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + index)
                TextureBinding(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, index))
            }
        } finally {
            GlStateManager._activeTexture(activeTexture)
        }

    private fun uniformBindings(): List<UniformBinding> =
        List(GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS)) { index ->
            UniformBinding(
                GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, index),
                GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_START, index),
                GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_SIZE, index),
            )
        }

    private fun integers(parameter: Int): IntArray =
        MemoryStack.stackPush().use { stack ->
            val result = stack.mallocInt(4)
            GL11.glGetIntegerv(parameter, result)
            IntArray(4) { result[it] }
        }

    private data class TextureBinding(
        val texture: Int,
        val sampler: Int,
    )

    private data class UniformBinding(
        val buffer: Int,
        val start: Long,
        val length: Long,
    )

    /**
     * Creates a context-bound snapshot before the isolated native GUI render begins.
     */
    companion object {
        /**
         * Returns a snapshot that the caller must close on the same render thread in finally or use.
         */
        fun capture(): MinecraftNativeFontGlState = MinecraftNativeFontGlState()
    }
}
