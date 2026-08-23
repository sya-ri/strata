package dev.s7a.strata.node

import dev.s7a.strata.element.Element
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Privileged retained capability that materializes only the direct children needed by the next measure pass.
 *
 * Implementations are owner-thread confined and must return immutable descriptions with stable keys when identity survives range movement.
 * The runtime validates and reconciles the returned sibling set before measurement, then owns the descriptions exactly like ordinary declarative children.
 * State changes that alter the returned set must invalidate [DirtyPhase.Measure].
 * The callback must not mutate session state or recursively operate on the owning tree.
 */
@InternalStrataRuntimeApi
public fun interface DynamicChildrenNode {
    /**
     * Returns the complete direct-child description set required for the next measure pass.
     *
     * @return immutable direct children in logical sibling order.
     * @throws Throwable when deferred application content or data access fails; the owning tree propagates the failure and performs ordinary cleanup.
     */
    public fun dynamicChildren(): List<Element>
}
