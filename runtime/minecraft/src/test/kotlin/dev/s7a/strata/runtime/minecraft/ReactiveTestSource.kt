package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Sequential host-test source that rejects duplicate external subscriptions.
 */
internal class ReactiveTestSource<T>(
    initial: T,
) : StateSource<T> {
    private var current = StateSnapshot(StateRevision(0), initial)
    private var observer: ((StateSnapshot<T>) -> Unit)? = null
    var subscriptions = 0
        private set

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        check(this.observer == null)
        this.observer = observer
        subscriptions += 1
        return StateSubscription(current) { this.observer = null }
    }

    /**
     * Publishes a later revision without requesting a frame or reopening the screen.
     */
    fun publish(value: T) {
        current = StateSnapshot(StateRevision(current.revision.value + 1), value)
        observer?.invoke(current)
    }
}
