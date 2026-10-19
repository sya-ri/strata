package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import java.util.Optional

/**
 * Completes exact sampled and composition pipelines with unblended RGBA8 output and a fullscreen triangle.
 * Callers configure distinct shader identities and their sampler layouts before borrowing this immutable description.
 */
@JvmSynthetic
internal fun RenderPipeline.Builder.portableOutput(): RenderPipeline =
    withDepthStencilState(Optional.empty())
        .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
        .withCull(false)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build()
