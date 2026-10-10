package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.synchronized
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Thread-safe test source with explicit revisions, counted subscriptions and intentionally retained stale callbacks.
 */
internal class CutoffTestSource<T>(
    initial: T,
) : StateSource<T> {
    private val monitor = Any()
    private var current = StateSnapshot(StateRevision(0), initial)
    private val observers = LinkedHashSet<(StateSnapshot<T>) -> Unit>()

    /**
     * Current subscription acquisitions.
     */
    var subscriptions = 0
        private set

    /**
     * Exactly-once source release count.
     */
    var releases = 0
        private set

    /**
     * Latest installed callback retained only to exercise late generation rejection.
     */
    var staleObserver: ((StateSnapshot<T>) -> Unit)? = null
        private set

    /**
     * Optional cleanup failure after the callback has been removed.
     */
    var closeFailure: Throwable? = null

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        val initial =
            synchronized(monitor) {
                subscriptions += 1
                observers.add(observer)
                staleObserver = observer
                current
            }
        return StateSubscription(initial) {
            synchronized(monitor) {
                check(observers.remove(observer))
                releases += 1
            }
            closeFailure?.let { throw it }
        }
    }

    /**
     * Publishes a strictly newer value under the same lock as subscription close, serializing callback starts.
     */
    fun publish(value: T) {
        synchronized(monitor) {
            check(current.revision.value < Long.MAX_VALUE)
            val next = StateSnapshot(StateRevision(current.revision.value + 1), value)
            current = next
            // Close uses this same lock, so no callback can start after its subscription returns from close.
            observers.toList().forEach { it(next) }
        }
    }
}
