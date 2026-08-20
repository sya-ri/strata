package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints

/**
 * Test-owned observation probe passed explicitly through an external element description.
 */
public class ExternalProbe {
    /**
     * Lifecycle events recorded by the probed node.
     */
    internal val lifecycle: MutableList<ExternalLifecycleEvent> = ArrayList()

    /**
     * The most recently created external modifier node.
     */
    internal var modifierNode: ExternalModifierNode? = null

    /**
     * External component nodes grouped by their typed identifiers.
     */
    internal val componentNodes: MutableMap<ExternalNodeId, ExternalNode> = HashMap()

    /**
     * Number of external component update hooks observed.
     */
    internal var componentUpdateCalls: Int = 0

    /**
     * Number of external modifier update hooks observed.
     */
    internal var modifierUpdateCalls: Int = 0

    /**
     * Logical direct-child counts observed by external component measure hooks.
     */
    internal val componentChildCounts: MutableList<Int> = ArrayList()

    /**
     * Exact constraints supplied to external component measure callbacks in order.
     */
    internal val componentMeasureConstraints: MutableList<Constraints> = ArrayList()

    /**
     * External component identifiers recorded in exact measure-callback order.
     */
    internal val componentMeasureOrder: MutableList<ExternalNodeId> = ArrayList()

    /**
     * Virtual direct-child counts observed by external modifier measure hooks.
     */
    internal val modifierVirtualChildCounts: MutableList<Int> = ArrayList()
}
