package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource

/**
 * Retained capability consuming one tree-owned, revisioned snapshot per source.
 *
 * The runtime shares subscriptions by source identity and commits values before deferred child construction.
 * Reads and callbacks are owner-thread confined; source callbacks never invoke this capability directly.
 * Implementations must invalidate affected phases when committed values change, and must not mutate sources.
 */
@InternalStrataRuntimeApi
public interface StateObserverNode {
    /**
     * Ordered caller-owned sources; repeated identities occupy separate value positions but share a subscription.
     */
    public val observedSources: List<StateSource<*>>

    /**
     * Receives the frame's immutable value list in source declaration order.
     * Values may be null and remain owned by their sources; the runtime never deep-copies them.
     */
    public fun commitObservedValues(values: List<Any?>)
}
