package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13

/**
 * Checks whether one immutable image fits the active OpenGL two-dimensional texture limit before direct-cache reservation.
 *
 * @param image candidate source borrowed on the render thread.
 * @return true when both source dimensions can be allocated as one RGBA texture.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftSampledImage(image: DrawImage): Boolean {
    RenderSystem.assertOnRenderThread()
    val maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
    return image.size.width <= maximum && image.size.height <= maximum
}

/**
 * Transfers an empty OpenGL storage owner before allocating and uploading one immutable portable image.
 *
 * The caller owns [pixels] throughout and seals its GUI generation after this call, including failures.
 * [retain] receives a non-owning texture-manager view and its sole native owner before any texture name is allocated.
 * All work belongs to the render thread; failed initialization leaves every allocated name with the receiving generation.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftPortableTexture(
    pixels: NativeImage,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
) {
    RenderSystem.assertOnRenderThread()
    val storage = FabricPortableNativeStorage()
    retain(storage.texture, storage)
    storage.initialize(pixels)
}

/**
 * Keeps a staged texture name separate from its borrowed AbstractTexture until fenced native destruction.
 */
@OptIn(InternalStrataRuntimeApi::class)
private class FabricPortableNativeStorage : NativeGuiResource {
    private var nativeId = 0
    private var closed = false

    /**
     * Non-owning registration view, constructed without allocating a native texture on the render thread.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture = FabricPortableBorrowedTexture(::textureId)

    /**
     * Allocates and uploads after ownership transfer while preserving the caller's OpenGL bindings.
     *
     * The borrowed image remains owned by the outer resource; failure never destroys a possibly used allocation eagerly.
     */
    @JvmSynthetic
    internal fun initialize(pixels: NativeImage) {
        RenderSystem.assertOnRenderThread()
        val maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
        require(pixels.width in 1..maximum && pixels.height in 1..maximum) {
            "A portable GUI image exceeds the active OpenGL texture extent limit."
        }
        FabricNativeCanvasGlState().use {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0)
            nativeId = TextureUtil.generateTextureId()
            check(nativeId != 0) { "Portable GUI texture allocation returned no OpenGL name." }
            TextureUtil.prepareImage(nativeId, pixels.width, pixels.height)
            pixels.upload(0, 0, 0, false)
        }
    }

    /**
     * Borrows the current native name on the render thread without allocating or transferring it.
     *
     * Access before allocation or after destruction is rejected.
     */
    @JvmSynthetic
    internal fun textureId(): Int {
        RenderSystem.assertOnRenderThread()
        check(closed.not() && nativeId != 0) { "Portable GUI texture storage is unavailable." }
        return nativeId
    }

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        if (nativeId != 0) {
            TextureUtil.releaseTextureId(nativeId)
            nativeId = 0
        }
        closed = true
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        check(closed) { "Portable GUI destruction is queried only after successful close." }
        return true
    }
}
