package dev.s7a.strata.runtime.minecraft.fabric;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Preserves primary failure identity across Fabric adapter cleanup.
 *
 * <p>This package-private helper retains no failure after each synchronous call returns.</p>
 */
final class FabricMinecraftFailures {
    private FabricMinecraftFailures() {
    }

    /**
     * Adds a distinct secondary failure without creating an identity cycle in the throwable graph.
     *
     * @param primary failure that must remain primary.
     * @param secondary failure considered for suppression.
     */
    static void addSuppressed(Throwable primary, Throwable secondary) {
        if (primary == secondary || reaches(primary, secondary) || reaches(secondary, primary)) {
            return;
        }
        primary.addSuppressed(secondary);
    }

    /**
     * Runs one native operation and always restores its borrowed state without replacing its failure.
     *
     * <p>Both callbacks execute synchronously on the caller's owner thread and are never retained or retried.
     * Cleanup also runs after a partially completed operation; its failure escapes only when the operation succeeded.</p>
     *
     * @param action borrowed native operation whose failure remains primary.
     * @param cleanup independent state restoration attempted exactly once.
     * @throws Throwable when either callback fails, suppressing a distinct cleanup failure onto the operation failure.
     */
    static void runWithCleanup(Runnable action, Runnable cleanup) throws Throwable {
        Throwable primary = null;
        try {
            action.run();
        } catch (Throwable failure) {
            primary = failure;
        }
        try {
            cleanup.run();
        } catch (Throwable failure) {
            if (primary == null) primary = failure;
            else addSuppressed(primary, failure);
        }
        if (primary != null) throw primary;
    }

    private static boolean reaches(Throwable root, Throwable target) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(root);
        while (pending.isEmpty() == false) {
            Throwable candidate = pending.removeLast();
            if (candidate == target) {
                return true;
            }
            if (seen.add(candidate)) {
                Throwable cause = candidate.getCause();
                if (cause != null) {
                    pending.add(cause);
                }
                Collections.addAll(pending, candidate.getSuppressed());
            }
        }
        return false;
    }
}
