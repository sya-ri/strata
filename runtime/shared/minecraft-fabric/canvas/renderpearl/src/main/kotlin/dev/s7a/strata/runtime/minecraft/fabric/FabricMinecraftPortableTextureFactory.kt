package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.texture.AbstractTexture
import org.joml.Matrix3x2f
import org.joml.Vector4f
import java.util.Optional

/**
 * Uploads only exact axis metadata for deferred GUI sampling, transferring ownership before allocation.
 * The shared initialization signature also serves adapters which materialize output from [size] and [source].
 * Returns true because this adapter samples the pinned source during GUI submission rather than an offscreen pass.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
@Suppress("UNUSED_PARAMETER")
internal fun initializeFabricMinecraftSampledTexture(
    indices: NativeImage,
    size: IntSize,
    source: AbstractTexture,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
): Boolean {
    initializeFabricMinecraftPortableTexture(indices, retain)
    return true
}

/**
 * Queues one exact sampled GUI quad using borrowed source and index textures under their existing generation fences.
 * Bounds are enclosing integer GUI edges; the lookup records physical half-open coverage and original-coordinate texels.
 */
@JvmSynthetic
internal fun drawFabricMinecraftExactSampledImage(
    graphics: GuiGraphicsExtractor,
    source: AbstractTexture,
    prepared: AbstractTexture,
    bounds: IntRect,
) {
    RenderSystem.assertOnRenderThread()
    graphics.guiRenderState.addGuiElement(
        BlitRenderState(
            samplingPipeline,
            TextureSetup.doubleTexture(source.getTextureView(), source.getSampler(), prepared.getTextureView(), prepared.getSampler()),
            Matrix3x2f(graphics.pose()),
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            0f,
            1f,
            0f,
            1f,
            -1,
            graphics.scissorStack.peek(),
        ),
    )
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
        texture = owned.allocate { device.createTexture({ "Strata immutable portable layer" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, pixels.width, pixels.height, 1, 1) }
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        textureView = owned.allocate { device.createTextureView(checkNotNull(texture)) }
        device.createCommandEncoder().writeToTexture(checkNotNull(texture), pixels)
    }

    /**
     * Records complete ordered RGBA8 composition into alternating owned destinations without native blending.
     * Source views belong to the caller's full-presentation pin; all four texture/view pairs transfer before use.
     * Every pass covers the complete target, preserving preceding pixels outside CPU-resolved physical coverage.
     */
    @JvmSynthetic
    internal fun initializeComposition(
        indices: NativeImage,
        factors: NativeImage,
        size: IntSize,
        sources: List<AbstractTexture?>,
    ) {
        RenderSystem.assertOnRenderThread()
        val device = RenderSystem.getDevice()
        val targets =
            FabricMinecraftCompositionTargets.create(
                owned,
                size,
                IntSize(indices.width, indices.height),
                IntSize(factors.width, factors.height),
                { extent -> device.createTexture({ "Strata ordered composition destination" }, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, extent.width, extent.height, 1, 1) },
                { label, extent -> device.createTexture({ label }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, extent.width, extent.height, 1, 1) },
                device::createTextureView,
            )
        val outputs = targets.destinations
        val (indexTexture, indexView) = targets.indices
        val (factorTexture, factorView) = targets.factors
        val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        val encoder = device.createCommandEncoder()
        encoder.writeToTexture(indexTexture, indices)
        encoder.writeToTexture(factorTexture, factors)
        encoder.clearColorTexture(outputs[0].first, Vector4f(0f, 0f, 0f, 0f))
        val compiled = RenderSystem.getCompiledPipeline(fabricMinecraftCompositionPipeline())
        sources.forEachIndexed { index, source ->
            val previous = outputs[index % 2].second
            val target = outputs[(index + 1) % 2].second
            encoder.createRenderPass({ "Strata ordered portable composition" }, target, Optional.empty()).use { pass ->
                pass.setPipeline(compiled)
                pass.setUniform("InSampler", source?.getTextureView() ?: previous, nearest)
                pass.setUniform("DestinationSampler", previous, nearest)
                pass.setUniform("IndexSampler", indexView, nearest)
                pass.setUniform("FactorSampler", factorView, nearest)
                pass.draw(3, 1, index * 3, 0)
            }
        }
        texture = outputs[sources.size % 2].first
        textureView = outputs[sources.size % 2].second
        sampler = nearest
    }
}

private val samplingPipeline: RenderPipeline =
    RenderPipeline
        .builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(minecraftResourceLocation("strata", "pipeline/sampled_gui"))
        .withFragmentShader(minecraftResourceLocation("strata", "core/sampled_gui"))
        .withBindGroupLayout(
            BindGroupLayout
                .builder()
                .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
                .build(),
        ).build()
