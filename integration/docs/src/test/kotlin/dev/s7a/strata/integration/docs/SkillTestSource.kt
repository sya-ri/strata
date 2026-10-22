package dev.s7a.strata.integration.docs

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Owner-thread publisher owned by independent skill acceptance tests; callbacks do not publish reentrantly.
 */
internal class SkillTestSource<T>(
    initial: T,
) : StateSource<T> {
    private var current = StateSnapshot(StateRevision(0), initial)
    private val observers = LinkedHashMap<Any, (StateSnapshot<T>) -> Unit>()

    /**
     * Number of live observations, including independent mappings of the same source.
     */
    val activeSubscriptions: Int get() = observers.size

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        val key = Any()
        observers[key] = observer
        return StateSubscription(current) { observers.remove(key) }
    }

    /**
     * Publishes a later immutable value without interacting with any screen.
     */
    fun publish(value: T) {
        current = StateSnapshot(StateRevision(current.revision.value + 1), value)
        for ((key, observer) in observers.toList()) {
            if (observers[key] === observer) observer(current)
        }
    }
}
