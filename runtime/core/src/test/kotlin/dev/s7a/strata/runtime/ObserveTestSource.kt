package dev.s7a.strata.runtime

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Test-owned revision source with observable subscription lifetime and sequential publisher callbacks.
 */
internal class ObserveTestSource<T>(
    initial: T,
) : StateSource<T> {
    private var snapshot = StateSnapshot(StateRevision(0), initial)
    private val observers = LinkedHashSet<(StateSnapshot<T>) -> Unit>()

    /**
     * Total established subscriptions, independent of active count.
     */
    var subscriptions = 0
        private set

    /**
     * Total released subscriptions.
     */
    var releases = 0
        private set

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        val initial =
            synchronized(this) {
                subscriptions += 1
                observers.add(observer)
                snapshot
            }
        return StateSubscription(initial) {
            synchronized(this) {
                observers.remove(observer)
                releases += 1
            }
        }
    }

    /**
     * Publishes one newer immutable value on the calling thread.
     */
    fun publish(value: T) {
        val callbacks: List<(StateSnapshot<T>) -> Unit>
        val next: StateSnapshot<T>
        synchronized(this) {
            next = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
            snapshot = next
            callbacks = observers.toList()
        }
        callbacks.forEach { callback -> callback(next) }
    }
}
