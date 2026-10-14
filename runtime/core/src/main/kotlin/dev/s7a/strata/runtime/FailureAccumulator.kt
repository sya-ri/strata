package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.identitySet

/**
 * Accumulates failures without self-suppression or duplicate throwable instances.
 * Identity storage belongs to this synchronous operation and is created only when a failure is recorded.
 */
internal class FailureAccumulator(
    initial: Throwable? = null,
) {
    private var seen: MutableSet<Throwable>? = null
    private val identities: MutableSet<Throwable>
        get() = seen ?: identitySet<Throwable>().also { seen = it }

    /**
     * The first failure observed.
     */
    var first: Throwable? = initial
        private set

    init {
        initial?.let(::markSeen)
    }

    /**
     * Records [failure], preserving the first failure and suppressing later distinct failures.
     *
     * @param failure the failure to record.
     */
    fun add(failure: Throwable) {
        if (identities.add(failure).not()) {
            return
        }
        val current = first
        if (current == null) {
            first = failure
            failure.suppressedExceptions.forEach(::markSeen)
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
        addOptional(runCatching(action).exceptionOrNull())
    }

    /**
     * Throws the first recorded failure.
     */
    fun throwFirst(): Nothing = throw checkNotNull(first) { "No failure was recorded." }

    /**
     * Throws the first recorded failure when one exists.
     */
    fun throwIfPresent() {
        first?.let { failure -> throw failure }
    }

    private fun addFlattened(failure: Throwable) {
        if (failure in identities) {
            return
        }
        add(failure)
        failure.suppressedExceptions.forEach(::addFlattened)
    }

    private fun markSeen(failure: Throwable) {
        if (identities.add(failure).not()) {
            return
        }
        failure.suppressedExceptions.forEach(::markSeen)
    }
}
