package dev.s7a.strata.state

import dev.s7a.strata.internal.platform.PlatformThreads
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.internal.platform.PlatformThreadLocal as ThreadLocal

/**
 * Runtime-owned dependency set for one owner-thread screen evaluator.
 * Successful evaluation replaces dependencies; failed evaluation retains them until terminal cleanup.
 * Closing releases every dependency without closing the caller-owned state and is idempotent.
 *
 * @param beforeMutation validates the session phase and enters its equality/reentrancy guard; failure must leave that guard unchanged.
 * @param validateMutation checks the current operation phase without entering a mutation guard.
 * @param afterMutation leaves the guard after an accepted mutation attempt and must not throw.
 * @param invalidated marks the screen dirty after an unequal assignment and must not throw or run application code.
 */
@InternalStrataRuntimeApi
public class StateObservation(
    private val beforeMutation: () -> Unit,
    private val afterMutation: () -> Unit,
    private val invalidated: () -> Unit,
    private val validateMutation: () -> Unit,
) : AutoCloseable {
    private val owner = PlatformThreads.current()
    private val dependencies = LinkedHashSet<MutableState<*>>()
    private var collecting: MutableSet<MutableState<*>>? = null
    private var closed = false

    /**
     * Installs this session's phase guard for all state writes on the owner thread.
     * The runtime must pair a successful call with [leaveOperation] in a finally block, including after terminal cleanup.
     * Nested operations from different sessions must leave in reverse order.
     */
    public fun enterOperation() {
        checkOwner()
        val guards = operations.get() ?: ArrayList<StateObservation>().also(operations::set)
        check((this in guards).not()) { "A state observation operation is already active." }
        guards.add(this)
    }

    /**
     * Removes the innermost operation guard on its owner thread.
     * This remains valid after [close] releases dependencies during terminal session cleanup.
     */
    public fun leaveOperation() {
        checkOwner()
        val guards = checkNotNull(operations.get()) { "No state observation operation is active." }
        check(guards.last() === this) { "State observation operations must leave in reverse order." }
        guards.removeAt(guards.lastIndex)
        if (guards.isEmpty()) operations.remove()
    }

    /**
     * Evaluates [content] synchronously and records exactly its state reads.
     * Requires the owner thread and a live, non-evaluating observation.
     * The result and any application failure are propagated unchanged; nested screen evaluations restore their caller's tracking context.
     */
    public fun <T> evaluate(content: () -> T): T {
        checkOwner()
        check(closed.not() && collecting == null) { "State observation is closed or already evaluating." }
        checkAccess()
        val previous = active.get()
        val reads = LinkedHashSet<MutableState<*>>()
        collecting = reads
        active.set(this)
        try {
            val result = content()
            dependencies.filter { (it in reads).not() }.forEach { state -> state.forget(this) }
            dependencies.retainAll(reads)
            return result
        } finally {
            collecting = null
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    override fun close() {
        checkOwner()
        check(collecting == null) { "State observation cannot close during evaluation." }
        if (closed) return
        closed = true
        val released = dependencies.toList()
        dependencies.clear()
        released.forEach { state -> state.forget(this) }
    }

    private fun checkOwner() {
        check(PlatformThreads.current() === owner) { "State observation requires its construction thread." }
    }

    /**
     * Validates the owning session and enters its mutation guard.
     */
    internal fun beginMutation() = beforeMutation()

    /**
     * Leaves the owning session's mutation guard.
     */
    internal fun endMutation() = afterMutation()

    /**
     * Marks the owning session dirty without executing application code.
     */
    internal fun invalidate() = invalidated()

    /**
     * Owns dynamically scoped read tracking and equality guards for the current thread.
     */
    internal companion object {
        private val active = ThreadLocal<StateObservation?>()
        private val comparing = ThreadLocal<Boolean?>()
        private val operations = ThreadLocal<MutableList<StateObservation>?>()

        /**
         * Records a state read in the innermost screen evaluation on this thread.
         */
        internal fun record(state: MutableState<*>) {
            val observation = active.get() ?: return
            checkNotNull(observation.collecting).add(state)
            if (observation.dependencies.add(state)) state.observe(observation)
        }

        /**
         * Rejects state access from arbitrary user equality code.
         */
        internal fun checkAccess() {
            check(comparing.get() != true) { "State access is forbidden during equality comparison." }
        }

        /**
         * Rejects writes during declarative screen evaluation.
         */
        internal fun checkMutation() {
            check(active.get() == null) { "State mutation is forbidden during screen evaluation." }
            operations.get()?.forEach { observation -> observation.validateMutation() }
        }

        /**
         * Executes caller equality under a thread-local guard, restoring it even after failure.
         */
        internal fun compare(equality: () -> Boolean): Boolean {
            checkAccess()
            comparing.set(true)
            return try {
                equality()
            } finally {
                comparing.remove()
            }
        }
    }
}
