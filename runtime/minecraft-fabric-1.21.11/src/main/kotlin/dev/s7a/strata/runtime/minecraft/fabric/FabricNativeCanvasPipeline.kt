package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction

/**
 * Configures straight RGBA writes without depth testing for the release's Canvas sampling pipeline.
 *
 * Construction is CPU-only and owns no native resources; the render device compiles the resulting description.
 * No blend, color conversion, or depth write may change the leased source pixels during capture.
 */
@JvmSynthetic
internal fun RenderPipeline.Builder.canvasOutput(): RenderPipeline.Builder =
    withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withoutBlend()
