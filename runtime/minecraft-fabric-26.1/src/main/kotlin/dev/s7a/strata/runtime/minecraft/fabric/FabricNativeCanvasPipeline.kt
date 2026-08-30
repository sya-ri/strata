package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import java.util.Optional

/**
 * Configures the release's explicit color and depth states for straight RGBA Canvas sampling.
 *
 * Construction is CPU-only and owns no native resources; the render device compiles the resulting description.
 * All color channels are written without blending and no depth attachment is used by the capture pass.
 */
@JvmSynthetic
internal fun RenderPipeline.Builder.canvasOutput(): RenderPipeline.Builder =
    withDepthStencilState(Optional.empty())
        .withColorTargetState(ColorTargetState(Optional.empty(), ColorTargetState.WRITE_ALL))
