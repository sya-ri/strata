package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureLease
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureOrigin
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureProvider
import dev.s7a.strata.runtime.minecraft.fabric.canvasSource
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30

/**
 * Repeatedly rejects an actual cube-map texture through the public Canvas source and loaded screen path.
 *
 * Each screen is rendered inside one client task so its expected preparation failure is caught before Minecraft's outer crash handler.
 * The external cube-map object remains alive until all failed capture leases and target permits retire through real native fences.
 * Binding state and the GL error flag are checked independently after every rejection; no screenshot or CPU substitute is involved.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasGlInputValidation : MinecraftCanvasInputValidation {
    // Expected native failures and independent cleanup failures must preserve their original Throwable identity.
    @Suppress("TooGenericExceptionCaught")
    override fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.waitFor { NativeCanvasDevices.retainedTargetCount() == 0 }
        val texture = context.onClient { createCubeMap() }
        val counts = Counts()
        var failure: Throwable? = null
        try {
            repeat(8) {
                context.onClient { rejectCubeMap(context, profile, cubeMapSource(texture, counts)) }
                context.waitFor { counts.acquired == counts.released && NativeCanvasDevices.retainedTargetCount() == 0 }
            }
            context.onClient {
                check(counts.acquired == 8 && counts.released == counts.acquired) { "Every invalid-source attempt must acquire and release exactly one native lease." }
                check(GL11.glGetError() == GL11.GL_NO_ERROR)
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.waitFor { counts.acquired == counts.released && NativeCanvasDevices.retainedTargetCount() == 0 } },
                { context.onClient { GL11.glDeleteTextures(texture) } },
            )
        }
    }

    // Source preparation failures must stay primary while the native screen's independent close paths are attempted.
    @Suppress("TooGenericExceptionCaught")
    private fun rejectCubeMap(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        source: CanvasSource,
    ) {
        val definition =
            ScreenDefinition("Native Canvas invalid GL input acceptance") {
                Stack { Canvas(source, IntSize(16, 16)) }
            }
        val temporary = createMinecraftScreen(definition, profile, parent = null)
        var primary: Throwable? = null
        try {
            context.setScreen(temporary)
            val minecraft = Minecraft.getInstance()
            val graphics = GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())
            val bindings = Bindings()
            val rejected = renderForValidation(temporary, graphics)
            check(rejected is IllegalArgumentException) { "A cube-map Canvas source must fail explicitly before native GUI output: $rejected" }
            bindings.requireRestored()
            check(GL11.glGetError() == GL11.GL_NO_ERROR) { "Rejecting a non-2D Canvas source leaked an OpenGL error into later UI work." }
            check(NativeCanvasDevices.retainedTargetCount() <= 3) { "One rejected source exceeded the per-Canvas target bound." }
        } catch (caught: Throwable) {
            primary = caught
            throw caught
        } finally {
            runCanvasTestCleanup(primary, { context.setScreen(null) }, temporary::close)
        }
    }

    private fun renderForValidation(
        screen: Screen,
        graphics: GuiGraphics,
    ): Throwable? = runCatching { screen.render(graphics, 0, 0, 0f) }.exceptionOrNull()

    private fun cubeMapSource(
        texture: Int,
        counts: Counts,
    ): CanvasSource =
        canvasSource(
            MinecraftCanvasTextureProvider {
                counts.acquired++
                object : MinecraftCanvasTextureLease {
                    override val textureId: Int = texture
                    override val size: IntSize = IntSize(2, 2)
                    override val origin: MinecraftCanvasTextureOrigin = MinecraftCanvasTextureOrigin.TopLeft
                    override val snapshot: DrawImage? = null
                    private var closed = false

                    override fun close() {
                        check(closed.not()) { "A rejected GL source lease was released twice." }
                        closed = true
                        counts.released++
                    }
                }
            },
        )

    // Native object creation may fail after the name is allocated; delete that local name before preserving the failure.
    @Suppress("TooGenericExceptionCaught")
    private fun createCubeMap(): Int {
        check(GL11.glGetError() == GL11.GL_NO_ERROR) { "GL input validation requires an initially clean native error state." }
        val previous = GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP)
        val texture = GL11.glGenTextures()
        try {
            GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, texture)
            check(GL11.glIsTexture(texture)) { "The native validation cube-map object was not created." }
            check(GL11.glGetError() == GL11.GL_NO_ERROR)
            return texture
        } catch (failure: Throwable) {
            GL11.glDeleteTextures(texture)
            throw failure
        } finally {
            GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, previous)
        }
    }

    private class Counts {
        var acquired = 0
        var released = 0
    }

    private class Bindings {
        private val activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        private val texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        private val cubeMap = GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP)
        private val drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        private val readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        private val viewport = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }

        /**
         * Checks only detached native bindings from the same client context and throws on any escaped mutation.
         */
        fun requireRestored() {
            check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == activeTexture) { "Invalid Canvas input changed the active texture unit." }
            check(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == texture) { "Invalid Canvas input changed the previous 2D texture binding." }
            check(GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP) == cubeMap) { "Invalid Canvas input changed the external cube-map binding." }
            check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == drawFramebuffer) { "Invalid Canvas input changed the native draw framebuffer." }
            check(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) == readFramebuffer) { "Invalid Canvas input changed the native read framebuffer." }
            val currentViewport = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }
            check(currentViewport.contentEquals(viewport)) { "Invalid Canvas input changed the native viewport." }
        }
    }
}
