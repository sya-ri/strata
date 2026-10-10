package dev.s7a.strata.runtime

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * One caller-owned source with independently asserted publication and release for portable validation controls.
 */
internal class DescriptionValidationSource<T>(initial: T) : StateSource<T> {
    private var snapshot = StateSnapshot(StateRevision(0), initial)
    private var observer: ((StateSnapshot<T>) -> Unit)? = null

    /**
     * Total actual upstream subscription acquisitions.
     */
    var subscriptions = 0
        private set

    /**
     * Total actual upstream terminal/removal releases.
     */
    var releases = 0
        private set

    override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
        check(this.observer == null)
        subscriptions += 1
        this.observer = observer
        return StateSubscription(snapshot) {
            releases += 1
            this.observer = null
        }
    }

    /**
     * Publishes a strictly later revision through the external callback boundary.
     */
    fun publish(value: T) {
        snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
        observer?.invoke(snapshot)
    }
}
