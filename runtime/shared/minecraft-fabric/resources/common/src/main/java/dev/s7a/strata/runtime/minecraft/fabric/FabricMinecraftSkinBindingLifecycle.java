package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic lookup lifecycle shared by the owner thread and one asynchronous completion callback.
 *
 * <p>Only {@link #publish(SkinCompletion)} may run away from the owner thread. Closing atomically replaces every active state, drops a queued native completion, and clears the retained immutable snapshot.</p>
 */
final class FabricMinecraftSkinBindingLifecycle {
    /**
     * One terminal result delivered by the asynchronous Minecraft skin lookup.
     */
    interface SkinCompletion {
    }

    /**
     * Successful Minecraft skin lookup retained only until the owner-thread frame boundary.
     */
    static final class SkinReady implements SkinCompletion {
        final FabricMinecraftSkinReference skin;

        /**
         * Retains a resolved descriptor until its owner-thread snapshot or terminal release.
         */
        SkinReady(FabricMinecraftSkinReference skin) {
            this.skin = skin;
        }
    }

    /**
     * Unusable or failed Minecraft skin lookup result.
     */
    enum SkinFailed implements SkinCompletion {
        INSTANCE
    }

    private interface SkinLifecycleState {
    }

    private enum SkinLookingUp implements SkinLifecycleState {
        INSTANCE
    }

    private static final class SkinCompleted implements SkinLifecycleState {
        private final SkinCompletion completion;

        private SkinCompleted(SkinCompletion completion) {
            this.completion = completion;
        }
    }

    private enum SkinResolved implements SkinLifecycleState {
        INSTANCE
    }

    private enum SkinClosed implements SkinLifecycleState {
        INSTANCE
    }

    private final AtomicReference<SkinLifecycleState> state = new AtomicReference<>(SkinLookingUp.INSTANCE);
    private MinecraftPlayerSkinBinding.Snapshot snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending.INSTANCE;

    /**
     * Publishes the sole asynchronous completion if lookup ownership remains active.
     *
     * @param completion terminal lookup result retained until the owner-thread frame boundary.
     * @return whether this lifecycle accepted the completion.
     */
    boolean publish(SkinCompletion completion) {
        Objects.requireNonNull(completion, "Minecraft player skin completion must not be null.");
        SkinLifecycleState current = state.get();
        while (current == SkinLookingUp.INSTANCE) {
            SkinCompleted completed = new SkinCompleted(completion);
            if (state.compareAndSet(current, completed)) {
                return true;
            }
            current = state.get();
        }
        return false;
    }

    /**
     * Transfers one accepted completion to the owner thread exactly once.
     *
     * @return queued completion, or null while lookup is pending, already resolved, or closed.
     */
    SkinCompletion drainCompletion() {
        SkinLifecycleState current = state.get();
        while (current instanceof SkinCompleted completed) {
            if (state.compareAndSet(current, SkinResolved.INSTANCE)) {
                return completed.completion;
            }
            current = state.get();
        }
        return null;
    }

    /**
     * Commits one immutable owner-thread snapshot after a completion has been drained.
     *
     * @param next normalized ready or failed snapshot.
     * @return whether a distinct active snapshot was retained.
     */
    boolean commitSnapshot(MinecraftPlayerSkinBinding.Snapshot next) {
        Objects.requireNonNull(next, "Minecraft player skin snapshot must not be null.");
        if (state.get() != SkinResolved.INSTANCE || snapshot.equals(next)) {
            return false;
        }
        snapshot = next;
        return true;
    }

    /**
     * Returns the currently retained owner-thread snapshot.
     *
     * @return pending, ready, or failed snapshot; close resets this value to pending.
     */
    MinecraftPlayerSkinBinding.Snapshot retainedSnapshot() {
        return snapshot;
    }

    /**
     * Atomically abandons lookup ownership and releases every queued or committed result.
     *
     * @return whether this call performed the active-to-closed transition.
     */
    boolean close() {
        SkinLifecycleState previous = state.getAndSet(SkinClosed.INSTANCE);
        if (previous == SkinClosed.INSTANCE) {
            return false;
        }
        snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending.INSTANCE;
        return true;
    }

    /**
     * Returns whether lookup ownership has been released.
     *
     * @return true only after the atomic closed transition.
     */
    boolean isClosed() {
        return state.get() == SkinClosed.INSTANCE;
    }
}
