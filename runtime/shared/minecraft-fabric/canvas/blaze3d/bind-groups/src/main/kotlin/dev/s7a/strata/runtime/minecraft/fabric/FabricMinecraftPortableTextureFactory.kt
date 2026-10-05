package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import java.util.Optional

/**
 * Enables bounded index-texture sampling on this device-command adapter without reading source pixels.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftExactSampling(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4_096 <=
        RenderSystem
            .getDevice()
            .deviceInfo.limits
            .maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM)
}

/**
 * Transfers an empty native owner before allocating the output and axis texture or recording their GPU work.
 * The source remains pinned by the caller, and the receiving output generation seals initialization even on failure.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftSampledTexture(
    indices: NativeImage,
    size: IntSize,
    source: AbstractTexture,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
) {
    val storage = FabricPortableNativeStorage()
    retain(storage.texture, storage)
    storage.initialize(indices, size, source)
}

/**
 * Checks whether one immutable image fits the active device's RGBA texture limit before direct-cache reservation.
 *
 * @param image candidate source borrowed on the render thread.
 * @return true when both source dimensions can be allocated as one RGBA texture.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftSampledImage(image: DrawImage): Boolean {
    RenderSystem.assertOnRenderThread()
    val maximum =
        RenderSystem
            .getDevice()
            .deviceInfo.limits
            .maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM)
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
    /**
     * Borrows the empty or initialized texture view without transferring storage or allocating a texture.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture
        field = Texture()

    /**
     * Initializes the retained owner on the render thread; failure preserves every partial allocation for fenced cleanup.
     */
    @JvmSynthetic
    internal fun initialize(pixels: NativeImage) {
        texture.initialize(pixels)
    }

    /**
     * Initializes GPU output and its bounded lookup while retaining every partial native allocation.
     */
    @JvmSynthetic
    internal fun initialize(
        indices: NativeImage,
        size: IntSize,
        source: AbstractTexture,
    ) {
        texture.initialize(indices, size, source)
    }

    @JvmSynthetic
    override fun close() {
        texture.destroy()
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean = texture.isDestroyed()

    private class Texture : AbstractTexture() {
        private var indexTexture: GpuTexture? = null
        private var indexView: GpuTextureView? = null
        private val owned = FabricMinecraftNativeStorage()

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
            texture = owned.allocate { device.createTexture({ "Strata immutable portable layer" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, pixels.width, pixels.height, 1, 1) }
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            textureView = owned.allocate { device.createTextureView(checkNotNull(texture)) }
            device.createCommandEncoder().writeToTexture(checkNotNull(texture), pixels)
        }

        /**
         * Uploads only axis metadata, then writes all output pixels in one unblended GPU pass.
         */
        @JvmSynthetic
        internal fun initialize(
            indices: NativeImage,
            size: IntSize,
            source: AbstractTexture,
        ) {
            RenderSystem.assertOnRenderThread()
            val device = RenderSystem.getDevice()
            texture = owned.allocate { device.createTexture({ "Strata exact sampled output" }, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, size.width, size.height, 1, 1) }
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            textureView = owned.allocate { device.createTextureView(checkNotNull(texture)) }
            indexTexture = owned.allocate { device.createTexture({ "Strata sampled axis indices" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, indices.width, indices.height, 1, 1) }
            indexView = owned.allocate { device.createTextureView(checkNotNull(indexTexture)) }
            check(
                device
                    .precompilePipeline(fabricMinecraftSamplingPipeline()) { _, stage ->
                        when (stage) {
                            ShaderType.VERTEX -> FabricNativeCanvasShaders.vertex.replace("#version 150", "#version 330")
                            ShaderType.FRAGMENT -> FabricMinecraftSamplingShaders.fragment.replace("#version 150", "#version 330")
                        }
                    }.isValid,
            ) { "Exact sampled-image pipeline compilation failed." }
            val encoder = device.createCommandEncoder()
            encoder.writeToTexture(checkNotNull(indexTexture), indices)
            encoder.createRenderPass({ "Strata exact sampled image" }, checkNotNull(textureView), Optional.empty()).use { pass ->
                pass.setPipeline(fabricMinecraftSamplingPipeline())
                pass.bindTexture("InSampler", source.getTextureView(), source.getSampler())
                pass.bindTexture("IndexSampler", checkNotNull(indexView), sampler)
                pass.draw(3, 1, 0, 0)
            }
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
            owned.close()
            textureView = null
            texture = null
            indexView = null
            indexTexture = null
        }

        /**
         * Acknowledges all original native allocations without waiting, after every close request has succeeded.
         *
         * Unknown or incomplete physical destruction retains the generation's permit through the caller's polling policy.
         */
        @JvmSynthetic
        internal fun isDestroyed(): Boolean {
            RenderSystem.assertOnRenderThread()
            return owned.isDestroyed()
        }

        @JvmSynthetic
        override fun close() = Unit
    }
}

private val samplingPipeline: RenderPipeline =
    RenderPipeline
        .builder()
        .withLocation(minecraftResourceLocation("strata", "pipeline/sampled_exact"))
        .withVertexShader(minecraftResourceLocation("strata", "core/canvas"))
        .withFragmentShader(minecraftResourceLocation("strata", "core/sampled_exact"))
        .withBindGroupLayout(
            BindGroupLayout
                .builder()
                .withSampler("InSampler")
                .withSampler("IndexSampler")
                .build(),
        ).withDepthStencilState(Optional.empty())
        .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
        .withCull(false)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build()

/**
 * Borrows an immutable pipeline description; compiled native programs belong to the device.
 */
@JvmSynthetic
internal fun fabricMinecraftSamplingPipeline(): RenderPipeline = samplingPipeline
