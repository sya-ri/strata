package dev.s7a.strata.state

/**
 * Subscription-local gate preventing a transformation already in flight from starting delivery after close.
 * Closing clears the callback under the same monitor used for delivery; source cleanup runs outside this monitor.
 */
internal class MappedStateDelivery<T>(
    observer: (StateSnapshot<T>) -> Unit,
) : AutoCloseable {
    private var observer: ((StateSnapshot<T>) -> Unit)? = observer

    /**
     * Delivers a transformed snapshot only while open, preserving upstream callback serialization.
     */
    fun deliver(snapshot: StateSnapshot<T>) {
        synchronized(this) { observer?.invoke(snapshot) }
    }

    override fun close() {
        synchronized(this) { observer = null }
    }
}
