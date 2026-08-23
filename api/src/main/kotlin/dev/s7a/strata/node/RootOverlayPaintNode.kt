package dev.s7a.strata.node

import dev.s7a.strata.render.RootOverlayPaintScope

/**
 * A node that emits screen-coordinate drawing commands after the complete retained tree.
 *
 * Root overlays are not enclosed by ancestor child clips and therefore suit transient surfaces such as tooltips and menus.
 * Implementations must not use this contract for ordinary post-child decoration, which belongs on [OverlayPaintNode].
 */
public interface RootOverlayPaintNode {
    /**
     * Paints this node's root overlay on the owning tree thread.
     *
     * The scope is valid only for this callback and failures propagate through the owning tree's paint failure contract.
     *
     * @param scope root-coordinate command collector with the current anchor and viewport.
     */
    public fun paintRootOverlay(scope: RootOverlayPaintScope)
}
