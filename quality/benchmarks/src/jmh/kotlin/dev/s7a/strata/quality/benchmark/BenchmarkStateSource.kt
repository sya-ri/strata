package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Single-owner deterministic publisher; its snapshot allocation is included in each measured update.
 */
internal class BenchmarkStateSource<T>(
    initial: T,
) : StateSource<T> {
    private var snapshot = StateSnapshot(StateRevision(0), initial)
    private var observer: ((StateSnapshot<T>) -> Unit)? = null

    /**
     * Whether the worker-owned fixture still holds its one upstream consumer.
     */
    val subscribed: Boolean get() = observer != null

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        check(this.observer == null)
        this.observer = observer
        return StateSubscription(snapshot) { this.observer = null }
    }

    /**
     * Publishes one later revision on the benchmark worker thread.
     */
    fun publish(value: T) {
        snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
        observer?.invoke(snapshot)
    }
}
