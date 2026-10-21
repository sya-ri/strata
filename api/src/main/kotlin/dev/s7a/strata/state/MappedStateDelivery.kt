package dev.s7a.strata.state

import dev.s7a.strata.internal.platform.synchronized

/**
 * Subscription-local gate preventing a transformation already in flight from admitting delivery after close.
 * Closing clears the callback under the admission monitor; admitted callbacks run outside it and may finish after close.
 */
internal class MappedStateDelivery<T>(
    observer: (StateSnapshot<T>) -> Unit,
) : AutoCloseable {
    private val monitor = Any()
    private var observer: ((StateSnapshot<T>) -> Unit)? = observer

    /**
     * Admits a transformed snapshot only while open, preserving upstream callback serialization.
     */
    fun deliver(snapshot: StateSnapshot<T>) {
        val current = synchronized(monitor) { observer }
        current?.invoke(snapshot)
    }

    override fun close() {
        synchronized(monitor) { observer = null }
    }
}
