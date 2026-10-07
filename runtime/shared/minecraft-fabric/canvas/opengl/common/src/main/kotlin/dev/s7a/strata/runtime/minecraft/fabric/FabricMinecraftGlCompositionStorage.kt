package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33

/**
 * Owns both RGBA8 destinations, two metadata textures and two framebuffer names inside one fenced portable generation.
 * Initialization records each name before another native operation can fail; no source, metadata image or screen is retained.
 * The device owns the one fixed shader program separately through terminal completion.
 * Independent release steps retain failed names for retry and acknowledge physical deletion only after every close succeeds.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftGlCompositionStorage : NativeGuiResource {
    private val textures = IntArray(4)
    private val framebuffers = IntArray(2)
    private var output = -1
    private var closed = false

    /**
     * Non-owning texture-manager view; reload and unregister cannot delete this generation's storage.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture = FabricPortableBorrowedTexture(::textureId)

    /**
     * Initializes and records the whole ordered tile while preserving all four texture units and drawing state.
     * Every source is synchronously borrowed; the caller seals initialization even after a native failure.
     */
    @JvmSynthetic
    internal fun initialize(
        indices: NativeImage,
        factors: NativeImage,
        size: IntSize,
        sources: List<AbstractTexture?>,
    ) {
        RenderSystem.assertOnRenderThread()
        FabricNativeCanvasGlState(textureUnits = 4).use {
            allocateTexture(0, size.width, size.height)
            allocateTexture(1, size.width, size.height)
            allocateTexture(2, indices.width, indices.height)
            indices.upload(0, 0, 0, false)
            allocateTexture(3, factors.width, factors.height)
            factors.upload(0, 0, 0, false)
            for (index in framebuffers.indices) {
                framebuffers[index] = GL30.glGenFramebuffers()
                check(framebuffers[index] != 0) { "Ordered composition framebuffer allocation failed." }
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffers[index])
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textures[index], 0)
                check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) { "Ordered composition framebuffer is incomplete." }
            }
            GL11.glViewport(0, 0, size.width, size.height)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glDisable(GL11.GL_DITHER)
            GL11.glColorMask(true, true, true, true)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffers[0])
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            val samplers = GL.getCapabilities().let { capabilities -> capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects }
            sources.forEachIndexed { index, source ->
                val previous = textures[index % 2]
                val target = (index + 1) % 2
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffers[target])
                bind(0, source?.getId() ?: previous, samplers)
                bind(1, previous, samplers)
                bind(2, textures[2], samplers)
                bind(3, textures[3], samplers)
                FabricNativeCanvasDriver.drawComposition(index)
                output = target
            }
        }
    }

    private fun allocateTexture(
        index: Int,
        width: Int,
        height: Int,
    ) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        RenderSystem.activeTexture(GL13.GL_TEXTURE0)
        textures[index] = TextureUtil.generateTextureId()
        check(textures[index] != 0) { "Ordered composition texture allocation failed." }
        TextureUtil.prepareImage(textures[index], width, height)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
    }

    private fun bind(
        unit: Int,
        texture: Int,
        samplers: Boolean,
    ) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        if (samplers) GL33.glBindSampler(unit, 0)
    }

    private fun textureId(): Int {
        RenderSystem.assertOnRenderThread()
        check(closed.not() && 0 <= output) { "Ordered composition storage is unavailable." }
        return textures[output]
    }

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        FabricMinecraftFailures.runWithCleanup(
            { release(framebuffers, GL30::glDeleteFramebuffers) },
            { release(textures, TextureUtil::releaseTextureId) },
        )
        closed = true
    }

    @Suppress("TooGenericExceptionCaught") // Every independent native name must be attempted and failed releases remain owned for retry.
    private fun release(names: IntArray, delete: (Int) -> Unit) {
        var failure: Throwable? = null
        for (index in names.indices) {
            if (names[index] == 0) continue
            try {
                delete(names[index])
                names[index] = 0
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
            }
        }
        failure?.let { throw it }
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        check(closed) { "Ordered composition destruction is queried only after successful close." }
        return true
    }
}
