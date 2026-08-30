package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasContext
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureLease
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureOrigin

// Why: partial native allocation and upload must release acquired resources even when the primary failure is an Error.

/**
 * Uploads independently specified color texels to a real native source and supplies a separate offscreen clear renderer.
 *
 * The source uses the release's native RGBA8 texture contract.
 * All calls run on the client thread; external texture and upload-image ownership transfer only on successful construction.
 * The immutable upload image remains alive until all real capture leases close and is never supplied as a headless snapshot.
 */
@Suppress("TooGenericExceptionCaught")
internal fun createMinecraftCanvasTestResources(): MinecraftCanvasTestResources {
    RenderSystem.assertOnRenderThread()
    val image = NativeImage(2, 2, false)
    image.setPixel(0, 0, 0xFFFF0000.toInt())
    image.setPixel(1, 0, 0xFF00FF00.toInt())
    image.setPixel(0, 1, 0xFF0000FF.toInt())
    image.setPixel(1, 1, 0xFFFFFF00.toInt())
    val device = RenderSystem.getDevice()
    val texture =
        try {
            device.createTexture("Canvas source", TextureFormat.RGBA8, 2, 2, 1)
        } catch (failure: Throwable) {
            image.close()
            throw failure
        }
    try {
        val encoder = device.createCommandEncoder()
        encoder.writeToTexture(texture, image)
    } catch (failure: Throwable) {
        texture.close()
        image.close()
        throw failure
    }
    return object : MinecraftCanvasTestResources {
        private var closed = false

        override val backend: MinecraftCanvasTestBackend = MinecraftCanvasTestBackend.parse(device.backendName)

        override val backendDescription: String = device.implementationInformation

        override fun lease(origin: MinecraftCanvasTextureOrigin): MinecraftCanvasTextureLease {
            check(closed.not()) { "An externally closed source cannot be captured." }
            return object : MinecraftCanvasTextureLease {
                override val texture: GpuTexture = texture
                override val size: IntSize = IntSize(2, 2)
                override val origin: MinecraftCanvasTextureOrigin = origin
                override val snapshot: DrawImage? = null

                override fun close() = Unit
            }
        }

        override fun render(context: MinecraftCanvasContext): DrawImage? {
            check(context.target.useDepth) { "The independent custom renderer requires its requested depth attachment." }
            context.encoder.clearColorTexture(checkNotNull(context.target.colorTexture), 0x8000FF00.toInt())
            return null
        }

        override fun close() {
            if (closed) return
            try {
                texture.close()
            } finally {
                image.close()
            }
            closed = true
        }
    }
}
