package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Operation owning recorded work, distinguishing pre-input geometry from a host frame.
 */
@InternalStrataRuntimeApi
public enum class UiRenderOperation {
    /**
     * Initial attachment or explicit retained-tree reconciliation.
     */
    Attach,

    /**
     * One host frame, including its cutoff and deferred declarations.
     */
    Frame,

    /**
     * Geometry or callbacks run while dispatching an input event.
     */
    InputGeometry,

    /**
     * Other standalone tree work and lifecycle cleanup.
     */
    Other,
}
