package dev.s7a.strata.state

import dev.s7a.strata.internal.platform.currentOwner
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caller-owned observable value whose reads and writes require its construction execution owner.
 * This is the physical construction thread unless a runtime explicitly creates it in a serial ownership scope.
 * Equal assignments do not invalidate observers; changed assignments invalidate every observing screen.
 * Screen evaluation and guarded equality reject mutation before changing the value.
 * A throwing equality comparison preserves the previous value and propagates the original failure.
 * Create this value outside screen evaluation so rebuilding a screen does not reset it.
 *
 * @param initialValue value retained until a successful unequal assignment.
 */
@OptIn(InternalStrataRuntimeApi::class)
public class MutableState<T> internal constructor(
    initialValue: T,
) : State<T> {
    private val owner = currentOwner()
    private var current = initialValue
    private val observations = LinkedHashSet<StateObservation>()
    private var observationPlan: ObservationPlan? = null

    override var value: T
        get() {
            checkAccess()
            StateObservation.record(this)
            return current
        }
        set(value) {
            update(value)
        }

    /**
     * Assigns a value under its execution owner through the same equality and session guards as [value].
     * Returns whether it changed so standard component states can notify their retained observers without comparing twice.
     */
    internal fun update(value: T): Boolean {
        checkAccess()
        StateObservation.checkMutation()
        val plan = captureObservationPlan()
        val owners = plan?.owners.orEmpty()
        var entered = 0
        try {
            for (observation in owners) {
                observation.beginMutation()
                entered += 1
            }
            val changed = StateObservation.compare { current == value }.not()
            if (changed) {
                current = value
                plan?.observations?.forEach(StateObservation::invalidate)
            }
            return changed
        } finally {
            while (0 < entered) {
                entered -= 1
                owners[entered].endMutation()
            }
        }
    }

    private fun captureObservationPlan(): ObservationPlan? {
        if (observations.isEmpty()) return null
        observationPlan?.let { return it }
        val active = observations.toList()
        val owners = LinkedHashSet<StateObservation>()
        active.forEach { owners.add(it.mutationOwner) }
        return ObservationPlan(active, owners.toList()).also { observationPlan = it }
    }

    private fun checkAccess() {
        check(currentOwner() == owner) { "State requires its construction execution owner." }
        StateObservation.checkAccess()
    }

    /**
     * Adds a screen dependency under its execution owner without invoking user code.
     */
    internal fun observe(observation: StateObservation) {
        checkAccess()
        if (observations.add(observation)) observationPlan = null
    }

    /**
     * Releases a screen dependency under its execution owner without disposing the caller-owned value.
     */
    internal fun forget(observation: StateObservation) {
        check(currentOwner() == owner) { "State requires its construction execution owner." }
        if (observations.remove(observation)) observationPlan = null
    }

    /**
     * Immutable routing for the current ordered observation identities and their fixed mutation-owner identities.
     * Successful admission/removal immediately drops the stored plan; duplicate changes preserve its order and identity.
     * The construction execution owner derives it lazily and retains at most N observations and U distinct owners.
     * Value, equality, permission and invalidation outcomes stay outside this derived presentation metadata.
     * An ongoing attempt alone may retain a replaced plan until its reverse-prefix finally exit.
     */
    private class ObservationPlan(
        val observations: List<StateObservation>,
        val owners: List<StateObservation>,
    )
}

/**
 * Creates a caller-owned observable value under the current execution owner.
 * Retain it outside screen evaluation; equal assignments leave observing screens clean.
 *
 * @param initialValue initial value retained by the returned state.
 * @return a new state with no observing screens.
 */
public fun <T> mutableStateOf(initialValue: T): MutableState<T> = MutableState(initialValue)
