package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32

/**
 * Implements Canvas capture for the Minecraft OpenGL framebuffer family.
 *
 * All calls run on the active render thread; targets are device-owned and source textures remain leased externally.
 * Two fixed shader variants are cached by image orientation until terminal completion and retain no frame or source state.
 * Sampling preserves straight RGBA, normalizes texel-row orientation, and never performs a CPU readback.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal object FabricNativeCanvasDriver : NativeCanvasDriver {
    private var topLeftProgram: FabricNativeCanvasGlProgram? = null
    private var bottomLeftProgram: FabricNativeCanvasGlProgram? = null

    @JvmSynthetic
    override fun createTarget(
        physicalSize: IntSize,
        depth: Boolean,
    ): NativeCanvasTarget {
        RenderSystem.assertOnRenderThread()
        FabricNativeCanvasShaders.requireSupportedExtent(physicalSize)
        return FabricNativeCanvasGlState().use {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0)
            FabricNativeCanvasTarget.create(createCanvasRenderTarget(physicalSize, depth))
        }
    }

    @JvmSynthetic
    override fun fence(): NativeCanvasFence {
        RenderSystem.assertOnRenderThread()
        val sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        check(sync != 0L) { "Canvas OpenGL fence creation failed." }
        GL11.glFlush()
        return FabricNativeCanvasGlFence(sync)
    }

    @JvmSynthetic
    override fun finish() {
        RenderSystem.assertOnRenderThread()
        GL11.glFinish()
        var failure: Throwable? = null
        try {
            topLeftProgram?.close()
            topLeftProgram = null
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            bottomLeftProgram?.close()
            bottomLeftProgram = null
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
        }
        failure?.let { throw it }
    }

    /**
     * Samples an externally leased image into a reserved offscreen target.
     *
     * The binding probe consumes its own unsupported-target error before restoring native state.
     * A pre-existing OpenGL error is reported before probing so unrelated failures are not silently attributed to the source.
     *
     * @throws IllegalArgumentException when the source is not a positive ordinary 2D RGBA8 image.
     * @throws IllegalStateException when pre-existing OpenGL state, shader compilation, or framebuffer setup fails.
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmSynthetic
    internal fun copy(
        lease: MinecraftCanvasTextureLease,
        target: FabricNativeCanvasTarget,
        retain: (AutoCloseable) -> Unit,
    ) {
        RenderSystem.assertOnRenderThread()
        val previousError = GL11.glGetError()
        check(previousError == GL11.GL_NO_ERROR) { "Canvas capture encountered a pre-existing OpenGL error: $previousError." }
        FabricNativeCanvasGlState().use {
            FabricNativeCanvasShaders.requireSupportedExtent(lease.size)
            require(GL11.glIsTexture(lease.textureId)) { "A Canvas source texture must exist in the active render context." }
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lease.textureId)
            val bindingError = GL11.glGetError()
            require(bindingError == GL11.GL_NO_ERROR) { "Canvas accepts only ordinary 2D textures; native binding failed: $bindingError." }
            require(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == lease.textureId) { "Canvas accepts only ordinary 2D textures." }
            require(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) == GL11.GL_RGBA8) {
                "Canvas accepts only RGBA8 straight-alpha color textures."
            }
            require(
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) == lease.size.width &&
                    GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) == lease.size.height,
            ) { "A Canvas lease extent must match native mip level zero." }
            require(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL) == 0) {
                "Canvas requires mip level zero to remain the texture base level."
            }
            bindTarget(target)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glColorMask(true, true, true, true)
            program(lease.origin).draw(target.size)
        }
    }

    /**
     * Invokes one render-thread callback with a cleared offscreen target and restores target bindings even when the callback fails.
     *
     * The device retains ownership of the target and renderer through their distinct capture and GUI completion fences.
     * The borrowed context expires before return; this method performs no CPU readback or direct GUI drawing.
     *
     * @param target exclusively borrowed target with its reserved lifetime permit.
     * @param logicalSize final positive logical destination extent.
     * @param frameTime timestamp shared by the actual native presentation.
     * @param renderer attachment-owned renderer invoked exactly once.
     * @return the renderer's optional immutable physical-size, top-left snapshot.
     * @throws Throwable when target setup, rendering, or native-state restoration fails; the device still fences issued capture work.
     */
    @JvmSynthetic
    internal fun render(
        target: FabricNativeCanvasTarget,
        logicalSize: IntSize,
        frameTime: FrameTime,
        renderer: MinecraftCanvasRenderer,
    ): DrawImage? {
        RenderSystem.assertOnRenderThread()
        return FabricNativeCanvasGlState().use {
            bindTarget(target)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glColorMask(true, true, true, true)
            GL11.glDepthMask(true)
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClearDepth(1.0)
            val mask = GL11.GL_COLOR_BUFFER_BIT or if (target.renderTarget.useDepth) GL11.GL_DEPTH_BUFFER_BIT else 0
            GL11.glClear(mask)
            val context = MinecraftCanvasContext.create(target.renderTarget, logicalSize, target.size, frameTime)
            try {
                renderer.render(context)
            } finally {
                context.expire()
            }
        }
    }

    @JvmSynthetic
    override fun drainRetirements() = Unit

    private fun bindTarget(target: FabricNativeCanvasTarget) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.renderTarget.frameBufferId)
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
            "The Canvas offscreen framebuffer is incomplete."
        }
        GL11.glViewport(0, 0, target.size.width, target.size.height)
    }

    private fun program(origin: MinecraftCanvasTextureOrigin): FabricNativeCanvasGlProgram =
        when (origin) {
            MinecraftCanvasTextureOrigin.TopLeft -> topLeftProgram ?: FabricNativeCanvasGlProgram.create(origin).also { topLeftProgram = it }
            MinecraftCanvasTextureOrigin.BottomLeft -> bottomLeftProgram ?: FabricNativeCanvasGlProgram.create(origin).also { bottomLeftProgram = it }
        }
}
