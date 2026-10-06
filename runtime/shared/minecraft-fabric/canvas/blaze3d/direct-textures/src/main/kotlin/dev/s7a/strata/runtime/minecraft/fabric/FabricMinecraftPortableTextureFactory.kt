package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.TextureFormat
import dev.s7a.strata.geometry.IntSize
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
 * Preserves exact CPU region sampling on adapters without a generation-owned GPU lookup pass.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftExactSampling(): Boolean {
    RenderSystem.assertOnRenderThread()
    return false
}

/**
 * Enforces the capability boundary before unsupported lookup allocation or source submission.
 * The partitioner always selects CPU region sampling on this adapter.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftSampledTexture(
    indices: NativeImage,
    size: IntSize,
    source: AbstractTexture,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
): Unit = error("This adapter uses exact CPU region sampling.")

/**
 * Owns exact-adapter allocations and preserves partial initialization until generation-fenced destruction.
 * Texture-manager close is inert; only the owning generation calls [destroy].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPortableNativeTexture : AbstractTexture() {
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
        texture = device.createTexture({ "Strata immutable portable layer" }, TextureFormat.RGBA8, pixels.width, pixels.height, 1)
        setClamp(true)
        setFilter(false, false)
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
        if (destruction == null) destruction = trackPortableDestruction(listOfNotNull(texture))
        texture?.close()
        texture = null
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
