package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage

/**
 * Internal local-coordinate drawing command retained before tree translation.
 */
internal sealed interface LocalDrawCommand {
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
}
