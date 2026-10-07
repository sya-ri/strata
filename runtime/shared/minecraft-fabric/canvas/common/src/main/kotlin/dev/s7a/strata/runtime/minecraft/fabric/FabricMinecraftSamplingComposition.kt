@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Proves that newly accelerated effects cannot split overlapping CPU composition into separately rounded layers.
 * Opaque fills are destination backgrounds or replacements; other exact opaque masks must be disjoint.
 * Any potentially translucent primitive retains CPU composition, even outside the admitted image's bounds.
 * Inserting a native barrier can split and change the rounding of those other commands' portable run.
 * The render-thread traversal borrows only the current command list and spends at most 8,192 command visits per frame.
 * Exhaustion selects the existing exact CPU path, and no proof state survives preparation.
 */
internal class FabricMinecraftSamplingComposition(
    private val commands: List<DrawCommand>,
) {
    private var remaining = 8_192

    /**
     * Checks one occurrence independently of equal or repeated command identities, without reading source pixels.
     * Clips are ignored conservatively; commands separated by native barriers are still checked for overlap.
     */
    @JvmSynthetic
    internal fun admits(
        occurrence: Int,
        command: DrawCommand.SampledImage,
    ): Boolean {
        for (index in commands.indices) {
            if (remaining == 0) return false
            remaining -= 1
            if (index == occurrence) continue
            val other = commands[index]
            val changesComposition =
                when (other) {
                    is DrawCommand.FillRectangle -> {
                        other.color.value ushr 24 != 255
                    }

                    is DrawCommand.SampledImage -> {
                        other.tint.value ushr 24 != 0 &&
                            (other.alphaCutoff != 1f || other.hasExactFabricSamplingEffects().not() || intersects(command, other.destination.left.toDouble(), other.destination.top.toDouble(), other.destination.right.toDouble(), other.destination.bottom.toDouble()))
                    }

                    is DrawCommand.BlitImage, is DrawCommand.BlitImagePixels, is DrawCommand.Platform -> {
                        true
                    }

                    is DrawCommand.PushClip, is DrawCommand.PushFractionalClip, DrawCommand.PopClip -> {
                        false
                    }
                }
            if (changesComposition) return false
        }
        return true
    }

    private fun intersects(
        command: DrawCommand.SampledImage,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Boolean {
        val target = command.destination
        return left < target.right.toDouble() && target.left.toDouble() < right && top < target.bottom.toDouble() && target.top.toDouble() < bottom
    }
}
