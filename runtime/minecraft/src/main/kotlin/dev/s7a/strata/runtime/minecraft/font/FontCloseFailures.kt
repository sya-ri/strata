package dev.s7a.strata.runtime.minecraft.font

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Preserves primary cleanup failures while suppressing distinct acyclic failures exactly once.
 */
internal class FontCloseFailures {
    private var first: Throwable? = null

    /**
     * Runs one cleanup action and retains its failure without preventing later cleanup.
     */
    fun attempt(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull() ?: return
        val primary = first
        if (primary == null) {
            first = failure
        } else if (reaches(primary, failure).not() && reaches(failure, primary).not()) {
            primary.addSuppressed(failure)
        }
    }

    /**
     * Rethrows the exact first failure after all registered cleanup work has been attempted.
     */
    fun throwFailure() {
        first?.let { throw it }
    }

    private fun reaches(
        initial: Throwable,
        target: Throwable,
    ): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.add(initial)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (current === target) return true
            if (visited.add(current)) {
                current.cause?.let(pending::add)
                current.suppressed.forEach(pending::add)
            }
        }
        return false
    }
}
