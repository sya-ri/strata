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
