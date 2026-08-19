package dev.s7a.strata.runtime.render

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor

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
}
