package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.shaders.ShaderType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import java.util.OptionalInt

/**
 * Transfers an empty native owner before allocating the output and axis texture or recording their GPU work.
 * The source remains pinned by the caller, and the receiving output generation seals initialization even on failure.
 * Returns false because this adapter materializes an offscreen output before ordinary GUI submission.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftSampledTexture(
    indices: NativeImage,
    size: IntSize,
    source: AbstractTexture,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
): Boolean {
    retainFabricMinecraftPortableStorage(retain).native.initialize(indices, size, source)
    return false
}

/**
 * Owns exact-adapter allocations and preserves partial initialization until generation-fenced destruction.
 * Texture-manager close is inert; only the owning generation calls [destroy].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPortableNativeTexture : FabricMinecraftPortableTextureStorage() {
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
        texture = owned.allocate { device.createTexture({ "Strata immutable portable layer" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, pixels.width, pixels.height, 1, 1) }
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
        texture = owned.allocate { device.createTexture({ "Strata exact sampled output" }, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, size.width, size.height, 1, 1) }
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        textureView = owned.allocate { device.createTextureView(checkNotNull(texture)) }
        val indexTexture = owned.allocate { device.createTexture({ "Strata sampled axis indices" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, indices.width, indices.height, 1, 1) }
        val indexView = owned.allocate { device.createTextureView(indexTexture) }
        check(
            device
                .precompilePipeline(fabricMinecraftSamplingPipeline()) { _, stage ->
                    when (stage) {
                        ShaderType.VERTEX -> FabricNativeCanvasShaders.vertex.replace("#version 150", "#version 330")
                        ShaderType.FRAGMENT -> FabricMinecraftSamplingShaders.fragment.replace("#version 150", "#version 330")
                    }
                }.isValid,
        ) { "Exact sampled-image pipeline compilation failed." }
        val vertexBuffer = owned.allocate { device.createBuffer({ "Strata sampled fullscreen triangle" }, GpuBuffer.USAGE_VERTEX, 1) }
        val encoder = device.createCommandEncoder()
        encoder.writeToTexture(indexTexture, indices)
        encoder.createRenderPass({ "Strata exact sampled image" }, checkNotNull(textureView), OptionalInt.empty()).use { pass ->
            pass.setPipeline(fabricMinecraftSamplingPipeline())
            pass.bindTexture("InSampler", source.getTextureView(), source.getSampler())
            pass.bindTexture("IndexSampler", indexView, sampler)
            pass.setVertexBuffer(0, vertexBuffer)
            pass.draw(0, 3)
        }
    }
}

private val samplingPipeline: RenderPipeline =
    RenderPipeline
        .builder()
        .withLocation(minecraftResourceLocation("strata", "pipeline/sampled_exact"))
        .withVertexShader(minecraftResourceLocation("strata", "core/canvas"))
        .withFragmentShader(minecraftResourceLocation("strata", "core/sampled_exact"))
        .withSampler("InSampler")
        .withSampler("IndexSampler")
        .canvasOutput()
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        .build()

/**
 * Borrows an immutable pipeline description; compiled native programs belong to the device.
 */
@JvmSynthetic
internal fun fabricMinecraftSamplingPipeline(): RenderPipeline = samplingPipeline
