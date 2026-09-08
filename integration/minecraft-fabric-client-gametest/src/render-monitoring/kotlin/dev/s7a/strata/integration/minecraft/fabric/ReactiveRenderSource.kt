package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Deterministic client-thread publisher; the acceptance scenario checks its real subscription count.
 */
internal class ReactiveRenderSource<T>(
    initial: T,
) : StateSource<T> {
    private var current = StateSnapshot(StateRevision(0), initial)
    private var observer: ((StateSnapshot<T>) -> Unit)? = null
    var subscriptions = 0
        private set

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        check(this.observer == null)
        subscriptions += 1
        this.observer = observer
        return StateSubscription(current) { this.observer = null }
    }

    /**
     * Advances a revision without calling any screen refresh or rendering method.
     */
    fun publish(value: T) {
        current = StateSnapshot(StateRevision(current.revision.value + 1), value)
        observer?.invoke(current)
    }
}
