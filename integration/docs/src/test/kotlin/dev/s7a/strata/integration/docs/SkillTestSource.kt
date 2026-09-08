package dev.s7a.strata.integration.docs

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Counted publisher owned by independent skill acceptance tests.
 */
internal class SkillTestSource<T>(
    initial: T,
) : StateSource<T> {
    private var current = StateSnapshot(StateRevision(0), initial)
    private var observer: ((StateSnapshot<T>) -> Unit)? = null

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        check(this.observer == null)
        this.observer = observer
        return StateSubscription(current) { this.observer = null }
    }

    /**
     * Publishes a later immutable value without interacting with any screen.
     */
    fun publish(value: T) {
        current = StateSnapshot(StateRevision(current.revision.value + 1), value)
        observer?.invoke(current)
    }
}
