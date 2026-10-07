package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import java.util.OptionalInt

/**
 * Admits bounded ordered composition only when every supported metadata and destination axis fits the native device.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftOrderedComposition(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4096 <= RenderSystem.getDevice().maxTextureSize
}

/**
 * Transfers an empty native owner before allocating both destinations and uploading exact metadata.
 * Every source remains borrowed through initialization and GUI consumption under the caller's existing fences.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftCompositionTexture(
    indices: NativeImage,
    factors: NativeImage,
    size: IntSize,
    sources: List<AbstractTexture?>,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
) {
    retainFabricMinecraftPortableStorage(retain).native.initializeComposition(indices, factors, size, sources)
}

private val compositionPipeline: RenderPipeline =
    RenderPipeline
        .builder()
        .withLocation(minecraftResourceLocation("strata", "pipeline/portable_composition"))
        .withVertexShader(minecraftResourceLocation("strata", "core/canvas"))
        .withFragmentShader(minecraftResourceLocation("strata", "core/portable_composition"))
        .withSampler("InSampler")
        .withSampler("DestinationSampler")
        .withSampler("IndexSampler")
        .withSampler("FactorSampler")
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withoutBlend()
        .withCull(false)
        .withVertexFormat(VertexFormat.builder().build(), VertexFormat.Mode.TRIANGLES)
        .build()

/**
 * Borrows the immutable four-sampler description; the host device owns compiled pipeline lifetime.
 */
@JvmSynthetic
internal fun fabricMinecraftCompositionPipeline(): RenderPipeline = compositionPipeline
