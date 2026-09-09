package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Distinguishes structural region evaluation from a direct argument binding.
 */
@InternalStrataRuntimeApi
public enum class ContentKind {
    /**
     * Application-owned structural declaration.
     */
    ObservedRegion,

    /**
     * Internal binding that evaluates a single component declaration.
     */
    StateComponent,
}
