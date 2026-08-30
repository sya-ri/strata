package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Checks whether one immutable image fits the active device's two-dimensional texture limit before direct-cache reservation.
 *
 * @param image candidate source borrowed on the render thread.
 * @return true when both source dimensions can be allocated as one RGBA texture.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftSampledImage(image: DrawImage): Boolean {
    RenderSystem.assertOnRenderThread()
    val maximum = RenderSystem.getDevice().maxTextureSize
    return image.size.width <= maximum && image.size.height <= maximum
}

/**
 * Transfers an empty GPU storage owner before allocating and uploading one immutable portable image.
 *
 * The caller owns [pixels] throughout and seals its GUI generation after this call, including failures.
 * [retain] receives a non-owning texture view and its sole native owner before any texture or view is allocated.
 * All work belongs to the render thread; failed initialization leaves every returned native allocation with the receiving generation.
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
 * Separates borrowed texture-manager access from staged, generation-owned native storage.
 */
@OptIn(InternalStrataRuntimeApi::class)
private class FabricPortableNativeStorage : NativeGuiResource {
    private val native = Texture()

    /**
     * Borrows the empty or initialized texture view without transferring storage or allocating a texture.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture
        get() = native

    /**
     * Initializes the retained owner on the render thread; failure preserves every partial allocation for fenced cleanup.
     */
    @JvmSynthetic
    internal fun initialize(pixels: NativeImage) {
        native.initialize(pixels)
    }

    @JvmSynthetic
    override fun close() {
        native.destroy()
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean = native.isDestroyed()

    @Suppress("TooGenericExceptionCaught")
    private class Texture : AbstractTexture() {
        private var destruction: FabricNativeCanvasDestruction? = null
        private var closeRequested = false

        /**
         * Allocates each native object into its owned field before the next operation can fail.
         *
         * The image is borrowed only for this render-thread upload; its outer resource owns its CPU lifetime.
         * Device sampler caches remain external, and no partial allocation is eagerly destroyed.
         */
        @JvmSynthetic
        internal fun initialize(pixels: NativeImage) {
            RenderSystem.assertOnRenderThread()
            val device = RenderSystem.getDevice()
            val maximum = device.maxTextureSize
            require(pixels.width <= maximum && pixels.height <= maximum) { "A portable GUI image exceeds the active device texture extent limit." }
            texture = device.createTexture({ "Strata immutable portable layer" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, pixels.width, pixels.height, 1, 1)
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            textureView = device.createTextureView(checkNotNull(texture))
            device.createCommandEncoder().writeToTexture(checkNotNull(texture), pixels)
        }

        /**
         * Requests independent native releases only after initialization and GUI-use fences complete.
         *
         * Successful release steps are not repeated after another step fails.
         * Destruction probes retain the original allocated objects before their mutable fields are cleared.
         */
        @JvmSynthetic
        internal fun destroy() {
            RenderSystem.assertOnRenderThread()
            if (closeRequested) return
            if (destruction == null) destruction = trackPortableDestruction(listOfNotNull(texture, textureView))
            var failure: Throwable? = null
            try {
                textureView?.close()
                textureView = null
            } catch (caught: Throwable) {
                failure = caught
            }
            try {
                texture?.close()
                texture = null
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
            }
            failure?.let { throw it }
            closeRequested = true
        }

        /**
         * Acknowledges all original native allocations without waiting, after every close request has succeeded.
         *
         * Unknown or incomplete physical destruction retains the generation's permit through the caller's polling policy.
         */
        @JvmSynthetic
        internal fun isDestroyed(): Boolean {
            RenderSystem.assertOnRenderThread()
            check(closeRequested) { "Portable GUI destruction is queried only after successful close." }
            return checkNotNull(destruction).isDestroyed()
        }

        @JvmSynthetic
        override fun close() = Unit
    }
}
