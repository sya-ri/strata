package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntRect

/**
 * Collects screen-coordinate commands for one root overlay.
 *
 * [size] is the complete root viewport and [anchorBounds] identifies the placed node requesting the overlay.
 * The scope has the same callback lifetime, owner-thread, and failure contract as [PaintScope].
 */
public interface RootOverlayPaintScope : PaintScope {
    /**
     * Placed half-open bounds of the requesting node in root coordinates.
     */
    public val anchorBounds: IntRect
}
