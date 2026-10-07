package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import java.util.Optional

/**
 * Admits bounded ordered composition only when every supported metadata and destination axis fits the native device.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftOrderedComposition(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4096 <= fabricMinecraftMaximumTextureSize()
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
        .withBindGroupLayout(
            BindGroupLayout
                .builder()
                .withSampler("InSampler")
                .withSampler("DestinationSampler")
                .withSampler("IndexSampler")
                .withSampler("FactorSampler")
                .build(),
        ).withDepthStencilState(Optional.empty())
        .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
        .withCull(false)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build()

/**
 * Borrows the immutable four-sampler description; the host device owns compiled pipeline lifetime.
 */
@JvmSynthetic
internal fun fabricMinecraftCompositionPipeline(): RenderPipeline = compositionPipeline
