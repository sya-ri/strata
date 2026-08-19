package dev.s7a.strata.integration.external

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
     * Virtual direct-child counts observed by external modifier measure hooks.
     */
    internal val modifierVirtualChildCounts: MutableList<Int> = ArrayList()
}
