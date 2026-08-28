package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Internal local-coordinate drawing command retained before tree translation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal sealed interface LocalDrawCommand {
    /**
     * Begins a local scoped paint clip without modifying retained drawing geometry.
     *
     * @property bounds local half-open clip rectangle, including empty clips.
     */
    data class PushClip(
        val bounds: IntRect,
    ) : LocalDrawCommand

    /**
     * Restores the enclosing clip after one synchronous paint block.
     */
    data object PopClip : LocalDrawCommand

    /**
     * Fills one local rectangle.
     *
     * @property bounds local rectangle bounds.
     * @property color fill color.
     */
    data class FillRectangle(
        val bounds: IntRect,
        val color: ArgbColor,
    ) : LocalDrawCommand

    /**
     * Retains one image source and local destination before tree translation.
     *
     * @property image the immutable source image.
     * @property source the nonempty source rectangle in image coordinates.
     * @property destination the nonempty local destination rectangle.
     */
    data class BlitImage(
        val image: DrawImage,
        val source: IntRect,
        val destination: IntRect,
    ) : LocalDrawCommand {
        init {
            validateBlitImage(image, source, destination)
        }
    }

    /**
     * Retains immutable sampled-image inputs until the owner thread translates them into tree coordinates.
     *
     * @property image the immutable source image retained by reference.
     * @property source the nonempty fractional source rectangle contained in the image.
     * @property destination the nonempty fractional local destination rectangle.
     * @property tint the multiplicative straight ARGB color.
     * @property alphaCutoff the inclusive minimum normalized alpha after tint multiplication.
     * @property orientation source-axis directions retained through translation and cached paint.
     * @throws IllegalArgumentException when the source, destination, or cutoff is invalid.
     */
    data class SampledImage(
        val image: DrawImage,
        val source: FloatRect,
        val destination: FloatRect,
        val tint: ArgbColor,
        val alphaCutoff: Float,
        val orientation: SampledImageOrientation,
    ) : LocalDrawCommand {
        init {
            validateSampledImage(image, source, destination, alphaCutoff)
        }
    }

    /**
     * Retains one opaque platform payload and its local bounds before tree translation.
     *
     * @property command immutable platform-owned payload.
     * @property bounds nonempty local bounds.
     */
    data class Platform(
        val command: PlatformDrawCommand,
        val bounds: IntRect,
    ) : LocalDrawCommand {
        init {
            require(0 < bounds.width && 0 < bounds.height) { "Platform draw bounds must be nonempty." }
        }
    }
}
