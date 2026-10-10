package dev.s7a.strata.integration.web

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Agent-confined test source delivering newer revisions even when their values are equal.
 * Subscription counts and optional cleanup failures are independent lifecycle oracles.
 */
internal class WebButtonMeasurementSource<T>(
    private var value: T,
    private val cleanupFailure: Throwable? = null,
) : StateSource<T> {
    private var revision = 0L
    private val observers = ArrayList<(StateSnapshot<T>) -> Unit>()

    /** Number of live source-owned subscriptions. */
    val active: Int get() = observers.size

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        observers.add(observer)
        return StateSubscription(StateSnapshot(StateRevision(revision), value)) {
            check(observers.remove(observer))
            cleanupFailure?.let { throw it }
        }
    }

    /** Publishes synchronously; runtime callbacks may only enqueue the detached snapshot. */
    fun publish(next: T) {
        revision += 1
        value = next
        val snapshot = StateSnapshot(StateRevision(revision), next)
        observers.toList().forEach { it(snapshot) }
    }
}
