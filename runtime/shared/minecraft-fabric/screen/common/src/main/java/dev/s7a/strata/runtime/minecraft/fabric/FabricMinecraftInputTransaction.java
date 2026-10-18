package dev.s7a.strata.runtime.minecraft.fabric;

import java.util.function.BooleanSupplier;

/**
 * Owns one non-reentrant inventory input transaction on its caller's client thread.
 * Completion runs only after successful host dispatch and retains the guard until it returns or throws.
 * Every exit drops the transaction and its offered hover reference, preserving the original failure.
 *
 * @param <I> native input metadata selected by the owning adapter
 * @param <H> adapter-owned hover binding
 */
abstract class FabricMinecraftInputTransaction<I, H> {
    /** Native metadata borrowed until host dispatch and completion both finish. */
    I input;

    /** First eligible hover binding offered by the current host dispatch. */
    H offeredHover;

    /**
     * Runs host dispatch and its successful completion after the adapter verifies owner-thread usability.
     *
     * @param current immutable native metadata for this dispatch
     * @param operation host dispatch whose boolean result is returned unchanged
     * @return host consumption result
     * @throws IllegalStateException when dispatch reenters an active transaction
     */
    final boolean dispatchInput(I current, BooleanSupplier operation) {
        if (input != null) {
            throw new IllegalStateException("Minecraft inventory input transactions are non-reentrant.");
        }
        input = current;
        offeredHover = null;
        boolean completed = false;
        try {
            boolean result = operation.getAsBoolean();
            completed = true;
            return result;
        } finally {
            try {
                if (completed) finishInput(current);
            } finally {
                input = null;
                offeredHover = null;
            }
        }
    }

    /** Completes successful host dispatch unless the adapter has closed during that dispatch. */
    abstract void finishInput(I current);
}
