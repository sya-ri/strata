package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Actual lazy declaration work reported only while a runtime installs an observer.
 */
@InternalStrataRuntimeApi
public enum class ContentWork {
    /**
     * One invocation of a virtualized row's application content.
     */
    RowEvaluation,
}
