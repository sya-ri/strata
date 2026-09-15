package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Optional deferred-region capability for tracking caller-owned state read by its content callback.
 * Successful evaluations replace dependencies; cached access preserves them and removal releases them before lifecycle cleanup.
 */
@InternalStrataRuntimeApi
public interface ReactiveContentNode : DeferredContentNode {
    /**
     * Marks cached declarations for reevaluation after an observed local state changes on the owner thread.
     * This callback must only enqueue content work and must not evaluate application code or throw.
     */
    public fun invalidateObservedContent()
}
