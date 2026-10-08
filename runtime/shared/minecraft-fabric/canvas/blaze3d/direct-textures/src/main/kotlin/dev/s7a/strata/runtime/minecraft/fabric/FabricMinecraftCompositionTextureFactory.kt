package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat

/**
 * Admits bounded ordered composition only when every supported metadata and destination axis fits the native device.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftOrderedComposition(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4096 <= RenderSystem.getDevice().maxTextureSize
}

private val compositionPipeline: RenderPipeline =
    RenderPipeline
        .builder()
        .withLocation(minecraftResourceLocation("strata", "pipeline/portable_composition"))
        .withVertexShader(minecraftResourceLocation("strata", "core/portable_composition"))
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
