package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Optional reporting of actual lazy content invocations, excluding cached child reads.
 * The runtime installs an observer only during diagnostics and clears it before disposal or monitor close.
 * Assignment must not evaluate content, call application code, or throw. Disabled work emits nothing.
 */
@InternalStrataRuntimeApi
public interface ContentWorkNode {
    /**
     * Owner-thread observer, or null during ordinary execution.
     */
    public var contentWorkObserver: ((ContentWork) -> Unit)?
}
