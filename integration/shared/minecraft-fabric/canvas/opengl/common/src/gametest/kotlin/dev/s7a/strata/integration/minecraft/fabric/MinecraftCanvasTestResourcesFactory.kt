package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasContext
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureLease
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureOrigin
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.system.MemoryStack

// Why: partial native allocation and upload must release acquired resources even when the primary failure is an Error.

/**
 * Creates a real GL texture with independently specified RGBA bytes and a direct offscreen clear renderer.
 *
 * Client-thread ownership transfers to the returned fixture only on success; its source remains immutable until close.
 * Linear external sampling parameters intentionally prove that Canvas's integer nearest sample pass does not mutate them.
 * Native allocation, upload, or deletion failures propagate to the loaded runner.
 */
@Suppress("TooGenericExceptionCaught")
internal fun createMinecraftCanvasTestResources(): MinecraftCanvasTestResources {
    RenderSystem.assertOnRenderThread()
    val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
    val texture = GL11.glGenTextures()
    try {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        uploadAndVerifyFixturePixels()
        check(GL11.glGetError() == GL11.GL_NO_ERROR) { "Native Canvas fixture texture upload failed." }
    } catch (failure: Throwable) {
        GL11.glDeleteTextures(texture)
        throw failure
    } finally {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
    }
    return object : MinecraftCanvasTestResources {
        private var closed = false

        override val inputValidation: List<MinecraftCanvasInputValidation> = listOf(MinecraftCanvasGlInputValidation)

        override val backend: MinecraftCanvasTestBackend = MinecraftCanvasTestBackend.OpenGl

        override val backendDescription: String =
            listOf("OpenGL", GL11.glGetString(GL11.GL_VERSION), GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER))
                .joinToString(" | ")

        override fun lease(origin: MinecraftCanvasTextureOrigin): MinecraftCanvasTextureLease {
            check(closed.not()) { "An externally closed source cannot be captured." }
            return object : MinecraftCanvasTextureLease {
                override val textureId: Int = texture
                override val size: IntSize = IntSize(2, 2)
                override val origin: MinecraftCanvasTextureOrigin = origin
                override val snapshot: DrawImage? = null

                override fun close() = Unit
            }
        }

        override fun render(context: MinecraftCanvasContext): DrawImage? {
            check(context.target.useDepth) { "The independent custom renderer requires its requested depth attachment." }
            GL11.glClearColor(0f, 1f, 0f, 128f / 255f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            return null
        }

        override fun close() {
            if (closed) return
            GL11.glDeleteTextures(texture)
            closed = true
        }
    }
}

/**
 * Uploads independently specified literal texels and reads the real source back before any Canvas sampler can use it.
 *
 * The bound external texture is borrowed on the render thread; this test-only check restores caller pixel storage and never changes production readback behavior.
 * Inherited row skips, row lengths, or pixel-buffer bindings cannot reinterpret the tightly packed sixteen-byte input.
 */
private fun uploadAndVerifyFixturePixels() {
    MinecraftCanvasGlPixelStorage().use {
        MemoryStack.stackPush().use { stack ->
            val expected =
                byteArrayOf(
                    -1,
                    0,
                    0,
                    -1,
                    0,
                    -1,
                    0,
                    -1,
                    0,
                    0,
                    -1,
                    -1,
                    -1,
                    -1,
                    0,
                    -1,
                )
            val bytes = stack.malloc(expected.size).put(expected).flip()
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes)
            val observed = stack.malloc(expected.size)
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, observed)
            expected.forEachIndexed { index, value ->
                check(observed.get(index) == value) { "The real OpenGL fixture source differs from its literal RGBA input at byte $index." }
            }
        }
    }
}
