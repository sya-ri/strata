package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand

/**
 * Proves a whole primitive leaves every canonical RGBA8 destination byte unchanged without reading source pixels.
 * Initialization clears transparent pixels to zero; integer source-over and sampled quantization preserve that invariant.
 * A sampled pass is redundant when tint alpha is zero or its maximum possible Float32 alpha is strictly below cutoff.
 * This does not replace ordered clip/geometry validation or remove individual partially covered source pixels.
 */
@JvmSynthetic
@Suppress("unused") // Synthetic geometry and axis-proof visitors call this exact no-op classification.
internal fun DrawCommand.isFabricMinecraftCompositionNoOp(): Boolean =
    when (this) {
        is DrawCommand.FillRectangle -> color.value ushr 24 == 0
        is DrawCommand.SampledImage -> {
            val alpha = tint.value ushr 24
            alpha == 0 || alpha.toFloat() / 255f < alphaCutoff
        }

        else -> false
    }
