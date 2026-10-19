package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import java.util.Optional

/**
 * Enables the ordered integer compositor only on adapters with complete native ownership and shader support.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftOrderedComposition(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4096 <= fabricMinecraftMaximumTextureSize()
}

private val compositionPipeline: RenderPipeline =
    RenderPipeline
        .builder()
        .withLocation(minecraftResourceLocation("strata", "pipeline/portable_composition"))
        .withVertexShader(minecraftResourceLocation("strata", "core/portable_composition"))
        .withFragmentShader(minecraftResourceLocation("strata", "core/portable_composition"))
        .withBindGroupLayout(
            BindGroupLayout
                .builder()
                .withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DestinationSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("IndexSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("FactorSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .build(),
        ).withDepthStencilState(Optional.empty())
        .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
        .withCull(false)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build()

/**
 * Borrows the fixed pipeline description; compiled programs follow the existing host device cache lifetime.
 */
@JvmSynthetic
internal fun fabricMinecraftCompositionPipeline(): RenderPipeline = compositionPipeline
