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
