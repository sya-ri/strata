package dev.s7a.strata.runtime.minecraft.fabric

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Preserves failure identity across synchronous native adapter cleanup without retaining an exception graph after return.
 * Each caller owns its graph; cause and suppression cycles are checked by identity rather than value equality.
 */
internal object FabricMinecraftFailures {
    /**
     * Suppresses a distinct secondary failure only when neither throwable already reaches the other.
     */
    @JvmStatic
    fun addSuppressed(
        primary: Throwable,
        secondary: Throwable,
    ) {
        if (primary === secondary || reaches(primary, secondary) || reaches(secondary, primary)) return
        primary.addSuppressed(secondary)
    }

    /**
     * Always restores borrowed native state, preserving an original operation failure over a distinct cleanup failure.
     * Both callbacks run synchronously on the caller's owner thread and are never retained or retried.
     */
    @JvmStatic
    fun runWithCleanup(
        action: Runnable,
        cleanup: Runnable,
    ) {
        val primary = runCatching { action.run() }.exceptionOrNull()
        val secondary = runCatching { cleanup.run() }.exceptionOrNull()
        if (primary != null) {
            if (secondary != null) addSuppressed(primary, secondary)
            throw primary
        }
        if (secondary != null) throw secondary
    }

    private fun reaches(
        root: Throwable,
        target: Throwable,
    ): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val candidate = pending.removeLast()
            if (candidate === target) return true
            if (seen.add(candidate)) {
                candidate.cause?.let(pending::add)
                pending.addAll(candidate.suppressed)
            }
        }
        return false
    }
}
