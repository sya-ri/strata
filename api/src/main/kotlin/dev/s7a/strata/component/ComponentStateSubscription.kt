package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Privileged idempotent release for one retained component-state observer.
 *
 * The runtime owns this handle and closes it on the state owner's thread during node disposal or source replacement.
 */
@InternalStrataRuntimeApi
public class ComponentStateSubscription internal constructor(
    private var release: (() -> Unit)?,
) : AutoCloseable {
    /**
     * Releases the observer once.
     */
    override fun close() {
        release?.invoke()
        release = null
    }
}
