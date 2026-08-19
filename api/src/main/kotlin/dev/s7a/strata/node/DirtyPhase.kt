package dev.s7a.strata.node

/**
 * A retained pipeline phase that can be invalidated by an element update or node state change.
 *
 * A measure invalidation expands to local layout, paint, and semantics and propagates measurement work to ancestors.
 * A layout invalidation dirties the node's local layout, paint, and semantics.
 * Ancestor traversal reaches that node only while it is currently placed, and descendants are not dirtied by this propagation.
 * Paint and semantics invalidations affect only their respective local caches.
 */
public enum class DirtyPhase {
    /**
     * Local measurement and the measurement of direct children requested by this node.
     */
    Measure,

    /**
     * Local child placement and accumulated bounds for placed descendants.
     */
    Layout,

    /**
     * The node's complete local drawing command list.
     */
    Paint,

    /**
     * The node's complete local unresolved semantics payload.
     */
    Semantics,
}
