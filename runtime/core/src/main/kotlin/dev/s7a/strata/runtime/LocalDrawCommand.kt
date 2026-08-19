package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor

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
}
