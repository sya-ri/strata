package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Owner-confined remote fixture publisher with a finite current subscriber table and observable terminal release.
 */
internal class RemoteStateSource<T>(
    initial: T,
    private val released: () -> Unit = {},
) : StateSource<T> {
    private var snapshot = StateSnapshot(StateRevision(0), initial)
    private val observers = linkedMapOf<Any, (StateSnapshot<T>) -> Unit>()

    /**
     * Live observations, used outside JMH timing to assert ownership and bounded retention.
     */
    internal val subscriptions: Int get() = observers.size

    /**
     * Current real value, used to publish a changed fixture declaration on every update.
     */
    internal val value: T get() = snapshot.value

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        val token = Any()
        observers[token] = observer
        return StateSubscription(snapshot) {
            observers.remove(token).let { removed -> check(removed == null || removed === observer) }
            released()
        }
    }

    /**
     * Publishes a strictly increasing revision, including allocation and notification in the measured operation.
     */
    internal fun publish(value: T) {
        snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
        observers.values.forEach { it(snapshot) }
    }
}
