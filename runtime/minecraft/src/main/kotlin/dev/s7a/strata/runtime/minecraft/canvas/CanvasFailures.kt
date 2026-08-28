package dev.s7a.strata.runtime.minecraft.canvas

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Owner-thread failure accumulator for best-effort native canvas cleanup.
 *
 * It retains only the current operation's exception graph, preserves the first instance, and never suppresses one instance twice.
 * Calling [attempt] always attempts its callback; [throwIfPresent] is the only operation that throws collected failures.
 */
internal class CanvasFailures(
    initial: Throwable? = null,
) {
    private val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    private var first: Throwable? = initial

    init {
        initial?.let(::remember)
    }

    /**
     * Attempts a borrowed synchronous cleanup operation and records its failure without stopping later cleanup.
     *
     * @param action callback invoked once on the calling owner thread, never retained.
     */
    fun attempt(action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let(::add)
    }

    /**
     * Preserves the first failure and appends later distinct instances in observation order.
     *
     * @param failure exception owned by its original thrower, retained only for this operation.
     */
    fun add(failure: Throwable) {
        if (seen.add(failure).not()) return
        val primary = first
        if (primary == null) first = failure else primary.addSuppressed(failure)
        failure.suppressed.forEach(::remember)
    }

    /**
     * Throws the exact primary failure when one was recorded, otherwise returns normally.
     */
    fun throwIfPresent() {
        first?.let { throw it }
    }

    private fun remember(failure: Throwable) {
        if (seen.add(failure)) failure.suppressed.forEach(::remember)
    }
}
