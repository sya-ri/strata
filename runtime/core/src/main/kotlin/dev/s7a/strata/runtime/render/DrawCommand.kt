package dev.s7a.strata.runtime.render

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.validateBlitImage

/**
 * A platform-neutral retained drawing command in accumulated tree coordinates.
 *
 * Commands are returned in parent-before-child and local emission order.
 * A backend must execute the list in that order.
 * The core does not add clipping, blending, or other backend policy.
 */
public sealed interface DrawCommand {
    /**
     * Fills a rectangle with one color.
     *
     * @property bounds the rectangle in accumulated tree coordinates.
     * @property color the fill color.
     */
    public data class FillRectangle(
        public val bounds: IntRect,
        public val color: ArgbColor,
    ) : DrawCommand

    /**
     * Copies an image source rectangle into a tree-coordinate destination rectangle.
     *
     * The source is nonempty and contained in [image].
     * The destination is nonempty and uses half-open tree coordinates.
     * Backends execute this command in the supplied command order; core does not clip or sample it.
     *
     * @property image the immutable source image retained by reference without copying pixels.
     * @property source the source rectangle in image pixel coordinates.
     * @property destination the destination rectangle in accumulated tree coordinates.
     * @throws IllegalArgumentException when the source is empty, outside [image], or the destination is empty.
     */
    public data class BlitImage(
        public val image: DrawImage,
        public val source: IntRect,
        public val destination: IntRect,
    ) : DrawCommand {
        init {
            validateBlitImage(image, source, destination)
        }
    }
}
