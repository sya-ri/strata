package dev.s7a.strata.state

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Runtime capability for a pure, single-input derived observation.
 * The source and transformation are immutable and caller-owned; runtimes evaluate committed inputs on their owner thread.
 * Implementations must also implement ordinary [StateSource.subscribe] with its complete lifetime contract.
 */
@InternalStrataRuntimeApi
public interface DerivedStateSource<out T> : StateSource<T> {
    /**
     * The input whose revision identifies this source's observation; dependency graphs must be acyclic.
     */
    public val upstream: StateSource<*>

    /**
     * Transforms one upstream value without reading live state or performing effects.
     * The value has the upstream's declared type, including null when permitted.
     * Failures propagate to the caller; a runtime must release its acquired observations on failure.
     */
    public fun derive(value: Any?): T
}
