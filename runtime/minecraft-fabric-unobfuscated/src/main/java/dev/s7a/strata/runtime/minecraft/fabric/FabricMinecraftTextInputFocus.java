package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Synchronizes one screen's current editable-focus identity with native text-input mode.
 *
 * <p>Gain notifications belong to the construction thread after retained and lifecycle transactions finish. Only the current detached identity is retained. Native gain may synchronously submit preedit and close the screen. Loss releases the identity before native notification so the screen can ignore its synchronous cancellation callback, including during deferred lifecycle cleanup.</p>
 */
final class FabricMinecraftTextInputFocus {
    private final Thread ownerThread = Thread.currentThread();
    private final Consumer<Boolean> notifyNative;
    private RuntimeTextInputFocus current;

    /**
     * Creates one owner-thread bridge without acquiring native focus.
     *
     * @param notifyNative borrowed callback receiving distinct loss and gain transitions.
     */
    FabricMinecraftTextInputFocus(Consumer<Boolean> notifyNative) {
        this.notifyNative = Objects.requireNonNull(notifyNative);
    }

    /**
     * Reports whether native preedit belongs to an active editable interval on this screen.
     *
     * @return whether the current interval is present.
     * @throws IllegalStateException when read from another thread.
     */
    boolean isActive() {
        checkOwner();
        return current != null;
    }

    /**
     * Applies a distinct editable interval after the caller's retained transaction has finished.
     *
     * <p>Switching non-null identities emits loss before gain even though the native listener is the same screen. Native failure releases the interval and attempts loss before propagating the exact primary failure.</p>
     *
     * @param focus current committed identity, or null without editable focus.
     * @throws IllegalStateException when called from another thread.
     * @throws Throwable when native notification fails; later distinct cleanup failures are suppressed.
     */
    void synchronize(RuntimeTextInputFocus focus) throws Throwable {
        checkOwner();
        if (current == focus) return;
        clear();
        if (focus == null) return;
        current = focus;
        try {
            notifyNative.accept(true);
        } catch (Throwable failure) {
            try {
                clear();
            } catch (Throwable cleanup) {
                FabricMinecraftFailures.addSuppressed(failure, cleanup);
            }
            throw failure;
        }
    }

    /**
     * Ends the current interval before detach, close, failure cleanup, or parent navigation.
     *
     * <p>Repeated calls and recursive loss are no-ops. The caller ignores native preedit while {@link #isActive()} is false, so loss can run during lifecycle cleanup without retained reentrancy.</p>
     *
     * @throws IllegalStateException when called from another thread.
     * @throws Throwable when native loss fails; the identity is already released.
     */
    void clear() throws Throwable {
        checkOwner();
        if (current == null) return;
        current = null;
        notifyNative.accept(false);
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Native text-input focus requires its owner thread.");
        }
    }
}
