package dev.s7a.strata.state

import dev.s7a.strata.internal.platform.PlatformThreads
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caller-owned observable value whose reads and writes require its construction thread.
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
    private val owner = PlatformThreads.current()
    private var current = initialValue
    private val observations = LinkedHashSet<StateObservation>()

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
     * Assigns an owner-thread value through the same equality and session guards as [value].
     * Returns whether it changed so standard component states can notify their retained observers without comparing twice.
     */
    internal fun update(value: T): Boolean {
        checkAccess()
        StateObservation.checkMutation()
        val active = observations.toList()
        val entered = ArrayList<StateObservation>()
        try {
            active.forEach { observation ->
                observation.beginMutation()
                entered.add(observation)
            }
            val changed = StateObservation.compare { current == value }.not()
            if (changed) {
                current = value
                active.forEach(StateObservation::invalidate)
            }
            return changed
        } finally {
            entered.asReversed().forEach(StateObservation::endMutation)
        }
    }

    private fun checkAccess() {
        check(PlatformThreads.current() === owner) { "State requires its construction thread." }
        StateObservation.checkAccess()
    }

    /**
     * Adds an owner-thread screen dependency without invoking user code.
     */
    internal fun observe(observation: StateObservation) {
        checkAccess()
        observations.add(observation)
    }

    /**
     * Releases an owner-thread screen dependency without disposing the caller-owned value.
     */
    internal fun forget(observation: StateObservation) {
        check(PlatformThreads.current() === owner) { "State requires its construction thread." }
        observations.remove(observation)
    }
}

/**
 * Creates a caller-owned observable value on the current thread.
 * Retain it outside screen evaluation; equal assignments leave observing screens clean.
 *
 * @param initialValue initial value retained by the returned state.
 * @return a new state with no observing screens.
 */
public fun <T> mutableStateOf(initialValue: T): MutableState<T> = MutableState(initialValue)
