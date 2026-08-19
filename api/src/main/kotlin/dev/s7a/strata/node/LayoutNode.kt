package dev.s7a.strata.node

import dev.s7a.strata.layout.LayoutScope

/**
 * A node that places the direct children it measured.
 *
 * Placement is retained for the current measured child set.
 * A clean layout pass may skip this callback.
 * A layout invalidation clears and recomputes this node's placements before traversal reaches currently placed descendants.
 */
public interface LayoutNode {
    /**
     * Places measured direct children on the owning tree thread.
     *
     * The scope is valid only for the duration of this callback.
     * Access after return or from another thread fails.
     * If a callback or scope operation throws after pipeline work begins, the owning tree is poisoned and cleanup is attempted.
     * This happens only when the exception escapes this callback.
     *
     * @param scope the current layout scope.
     */
    public fun layout(scope: LayoutScope)
}
