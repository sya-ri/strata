package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Replays only native diagnostic drawing into an owned floating-point color target with the original native shaders and blending.
 * Opaque probe texels isolate shader output from RGBA8 export without replacing any renderer algorithm or acceptance expectation.
 * Work is render-thread confined; temporary GPU and native-memory resources are released and affected GL state is restored on every exit.
 */
internal object MinecraftNativeFontFloatTarget {
    /**
     * Writes width, height, then top-down RGBA IEEE754 floats in big-endian order to a diagnostic-only binary file.
     * The caller supplies the same native-only drawing used for the ordinary probe screen; no captured data enters a portable render input.
     */
    fun capture(
        output: Path,
        draw: (GuiGraphics) -> Unit,
    ) = capture(output, "native-color-probes", MinecraftFontParityFixture.viewport, draw)

    /**
     * Captures the unchanged complete native scene at the active physical size and GUI density.
     * The callback is the same row drawing used by the native screen; precision metadata records actual native atlas extents.
     */
    fun captureScene(
        output: Path,
        scale: Int,
        draw: (GuiGraphics) -> Unit,
        atlasSize: () -> IntSize = { MinecraftNativeFontAtlases.capture(output) },
    ) {
        val minecraft = Minecraft.getInstance()
        check(minecraft.window.guiScale == scale.toDouble()) { "Native float capture GUI density differs from the requested scene." }
        val size = IntSize(minecraft.window.width, minecraft.window.height)
        capture(output, "font-native-$scale", size, draw)
        val atlas = atlasSize()
        Files.writeString(
            output.resolve("native-float-state-$scale.properties"),
            "scale=$scale\nwidth=${size.width}\nheight=${size.height}\nsubpixelBits=${GL11.glGetInteger(GL11.GL_SUBPIXEL_BITS)}\nmaxAtlasWidth=${atlas.width}\nmaxAtlasHeight=${atlas.height}\ncolorFormat=RGBA32F\npreparationThreads=1\n",
        )
    }

    private fun capture(
        output: Path,
        name: String,
        size: IntSize,
        draw: (GuiGraphics) -> Unit,
    ) {
        RenderSystem.assertOnRenderThread()
        val target = object : RenderTarget(Minecraft.getInstance().mainRenderTarget.useDepth) {}
        MemoryStack.stackPush().use { stack ->
            val viewport = stack.mallocInt(4)
            val clearColor = stack.mallocFloat(4)
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor)
            val drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
            val readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
            val texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            val alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
            val depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
            val blend = GL11.glIsEnabled(GL11.GL_BLEND)
            val sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
            val destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
            val sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
            val destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
            val shader = checkNotNull(RenderSystem.getShader())
            try {
                target.createBuffers(size.width, size.height, Minecraft.ON_OSX)
                RenderSystem.bindTexture(target.colorTextureId)
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, size.width, size.height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L)
                target.bindWrite(true)
                target.checkStatus()
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, stack.floats(0f, 0f, 0f, 0f))
                if (target.useDepth) GL30.glClearBufferfv(GL11.GL_DEPTH, 0, stack.floats(1f))
                val minecraft = Minecraft.getInstance()
                val graphics = GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())
                draw(graphics)
                graphics.flush()
                writePixels(output, name, target)
            } finally {
                target.destroyBuffers()
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
                RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
                RenderSystem.bindTexture(texture)
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment)
                RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
                if (depthTest) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
                if (blend) RenderSystem.enableBlend() else RenderSystem.disableBlend()
                RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha)
                RenderSystem.setShader { shader }
            }
        }
    }

    private fun writePixels(
        output: Path,
        name: String,
        target: RenderTarget,
    ) {
        val pixels = MemoryUtil.memAllocFloat(target.width * target.height * 4)
        try {
            GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RGBA, GL11.GL_FLOAT, pixels)
            DataOutputStream(Files.newOutputStream(output.resolve("$name.rgba32f")).buffered()).use { stream ->
                stream.writeInt(target.width)
                stream.writeInt(target.height)
                for (y in 0 until target.height) {
                    for (x in 0 until target.width) {
                        val index = ((target.height - y - 1) * target.width + x) * 4
                        repeat(4) { component -> stream.writeFloat(pixels[index + component]) }
                    }
                }
            }
            RenderSystem.bindTexture(target.colorTextureId)
            val format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
            check(format == GL30.GL_RGBA32F) { "Native float diagnostic target did not retain RGBA32F storage." }
            Files.writeString(output.resolve("$name-float-state.txt"), "colorInternalFormat=$format; stored=big-endian width:int,height:int,top-down RGBA:float32\n${MinecraftNativeFontShaderState.capture()}")
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }
}
