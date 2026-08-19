package dev.s7a.strata.runtime

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Accumulates failures without self-suppression or duplicate throwable instances.
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
            markSeen(failure)
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
            failure.suppressed.forEach(::markSeen)
        } else {
            current.addSuppressed(failure)
        }
    }

    /**
     * Records [failure] when it exists and flattens its distinct suppressed graph onto an existing primary failure.
     *
     * @param failure an optional failure returned by a cleanup operation.
     */
    fun addOptional(failure: Throwable?) {
        if (failure == null) {
            return
        }
        if (first == null) {
            add(failure)
            return
        }
        addFlattened(failure)
    }

    /**
     * Executes [action] and records a thrown failure.
     *
     * @param action the callback that may fail.
     */
    fun capture(action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let { failure ->
            addOptional(failure)
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

    private fun addFlattened(failure: Throwable) {
        if (seen.contains(failure)) {
            return
        }
        val nested = failure.suppressed.toList()
        add(failure)
        nested.forEach(::addFlattened)
    }

    private fun markSeen(failure: Throwable) {
        if (seen.add(failure).not()) {
            return
        }
        failure.suppressed.forEach(::markSeen)
    }
}
