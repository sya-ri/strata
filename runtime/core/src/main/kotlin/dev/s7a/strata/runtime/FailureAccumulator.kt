package dev.s7a.strata.runtime

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Accumulates cleanup failures without self-suppression or duplicate throwable instances.
 */
internal class FailureAccumulator(
    initial: Throwable? = null,
) {
    private val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * The first failure observed.
     */
    var first: Throwable? = initial
        private set

    init {
        initial?.let { failure ->
            seen.add(failure)
            failure.suppressed.forEach(seen::add)
        }
    }

    /**
     * Records [failure], preserving the first failure and suppressing later distinct failures.
     *
     * @param failure the failure to record.
     */
    fun add(failure: Throwable) {
        if (seen.add(failure).not()) {
            return
        }
        val current = first
        if (current == null) {
            first = failure
            failure.suppressed.forEach(seen::add)
        } else {
            current.addSuppressed(failure)
        }
    }

    /**
     * Records [failure] when it exists.
     *
     * @param failure an optional failure returned by a cleanup operation.
     */
    fun addOptional(failure: Throwable?) {
        if (failure != null) {
            add(failure)
            failure.suppressed.forEach { suppressed -> add(suppressed) }
        }
    }

    /**
     * Executes [action] and records a thrown failure.
     *
     * @param action the callback that may fail.
     */
    fun capture(action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let { failure ->
            add(failure)
        }
    }

    /**
     * Throws the first recorded failure.
     */
    fun throwFirst(): Nothing {
        val failure = first
        if (failure != null) {
            throw failure
        }
        throw IllegalStateException("No failure was recorded.")
    }

    /**
     * Throws the first recorded failure when one exists.
     */
    fun throwIfPresent() {
        first?.let { failure -> throw failure }
    }
}
