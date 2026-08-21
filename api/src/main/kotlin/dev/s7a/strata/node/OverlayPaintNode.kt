package dev.s7a.strata.node

import dev.s7a.strata.render.PaintScope

/**
 * A node that emits local drawing commands after its effective descendants.
 *
 * One successful callback produces the node's complete local overlay display list.
 * The runtime retains that list until paint invalidation and combines it with the node's current accumulated bounds on each paint pass.
 */
public interface OverlayPaintNode {
    /**
     * Paints this node's overlay in local coordinates on the owning tree thread after its effective descendants.
     *
     * The scope is valid only for the duration of this callback.
     * Access after return or from another thread fails.
     * If a callback or scope operation throws after pipeline work begins, the owning tree is poisoned and cleanup is attempted.
     * This happens only when the exception escapes this callback.
     *
     * @param scope the local command collector.
     */
    public fun paintOverlay(scope: PaintScope)
}
