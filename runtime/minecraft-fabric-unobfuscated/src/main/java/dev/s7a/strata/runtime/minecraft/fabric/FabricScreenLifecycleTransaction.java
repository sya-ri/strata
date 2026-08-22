package dev.s7a.strata.runtime.minecraft.fabric;

import java.util.ArrayDeque;
import java.util.function.Supplier;

/**
 * Serializes native screen lifecycle changes requested while common host work is active.
 *
 * <p>The transaction is package-private and bound to its creator thread. Attach, detach, and close callbacks are released when close is attempted; navigation is retained until its independent attempt. Attach and detach requests preserve declaration order, while close supersedes pending nonterminal work. Close-then-navigate always attempts cleanup before navigation and preserves the first distinct failure as primary.</p>
 */
final class FabricScreenLifecycleTransaction {
    private final Thread ownerThread = Thread.currentThread();
    private final ArrayDeque<Action> pending = new ArrayDeque<>();
    private Runnable attachCallback;
    private Runnable detachCallback;
    private Runnable closeCallback;
    private Runnable navigateCallback;
    private boolean active;
    private boolean terminalRequested;
    private boolean closeAttempted;
    private boolean navigationRequested;
    private boolean navigationAttempted;

    private enum Action {
        ATTACH,
        DETACH,
        CLOSE,
        CLOSE_THEN_NAVIGATE,
        NAVIGATE
    }

    private FabricScreenLifecycleTransaction(
            Runnable attach,
            Runnable detach,
            Runnable close,
            Runnable navigate) {
        attachCallback = attach;
        detachCallback = detach;
        closeCallback = close;
        navigateCallback = navigate;
    }

    /**
     * Creates one creator-thread lifecycle transaction around four native/common callbacks.
     *
     * @param attach callback that attaches common state.
     * @param detach callback that transiently detaches common state.
     * @param close callback that terminally releases state.
     * @param navigate callback that installs the retained parent.
     * @return a new inactive transaction retaining callbacks until their terminal use.
     */
    static FabricScreenLifecycleTransaction create(
            Runnable attach,
            Runnable detach,
            Runnable close,
            Runnable navigate) {
        return new FabricScreenLifecycleTransaction(attach, detach, close, navigate);
    }

    /**
     * Reports whether one transaction body or its deferred lifecycle callbacks are active.
     *
     * @return true only during {@link #run(Supplier)}.
     * @throws IllegalStateException when called away from the creator thread.
     */
    boolean isActive() {
        requireOwnerThread();
        return active;
    }

    /**
     * Runs one non-reentrant host operation and drains every lifecycle request before returning.
     *
     * @param action synchronous operation executed before deferred lifecycle callbacks.
     * @param <T> operation result type.
     * @return the operation result when the operation and deferred callbacks succeed.
     * @throws IllegalStateException when called away from the creator thread or reentrantly.
     * @throws Throwable when the operation or a deferred callback fails; later distinct failures are suppressed on the first failure.
     */
    <T> T run(Supplier<T> action) throws Throwable {
        requireOwnerThread();
        checkState(active == false, "Fabric Minecraft screen host operations are non-reentrant.");
        active = true;
        T result = null;
        Throwable primary = null;
        try {
            result = action.get();
        } catch (Throwable failure) {
            primary = failure;
        }
        try {
            primary = addFailure(primary, drain());
        } finally {
            active = false;
        }
        if (primary != null) {
            throw primary;
        }
        return result;
    }

    /**
     * Queues one attach after the active host operation.
     *
     * @throws IllegalStateException when called outside an active transaction, away from the creator thread, or after terminal close was requested.
     */
    void requestAttach() {
        requireRequestContext();
        checkState(terminalRequested == false, "A closing Fabric Minecraft screen cannot be added again.");
        enqueueDistinct(Action.ATTACH);
    }

    /**
     * Queues one transient detach after the active host operation.
     *
     * @throws IllegalStateException when called outside an active transaction or away from the creator thread.
     */
    void requestDetach() {
        requireRequestContext();
        if (terminalRequested) {
            return;
        }
        enqueueDistinct(Action.DETACH);
    }

    /**
     * Queues terminal cleanup without native navigation.
     *
     * @throws IllegalStateException when called outside an active transaction or away from the creator thread.
     */
    void requestClose() {
        requireRequestContext();
        if (terminalRequested) {
            return;
        }
        terminalRequested = true;
        pending.clear();
        pending.addLast(Action.CLOSE);
    }

    /**
     * Queues terminal cleanup followed by exactly one native parent navigation attempt.
     *
     * @throws IllegalStateException when called outside an active transaction or away from the creator thread.
     */
    void requestCloseThenNavigate() {
        requireRequestContext();
        if (navigationRequested || navigationAttempted) {
            return;
        }
        navigationRequested = true;
        pending.clear();
        if (closeAttempted) {
            pending.addLast(Action.NAVIGATE);
            return;
        }
        terminalRequested = true;
        pending.addLast(Action.CLOSE_THEN_NAVIGATE);
    }

    /**
     * Reports whether the active operation must stop using the old screen because detach or terminal work is queued.
     *
     * @return true when a detach, close, or navigation action is pending.
     * @throws IllegalStateException when called outside an active transaction or away from the creator thread.
     */
    boolean hasPendingExit() {
        requireRequestContext();
        for (Action action : pending) {
            if (action != Action.ATTACH) {
                return true;
            }
        }
        return false;
    }

    private Throwable drain() {
        Throwable primary = null;
        while (pending.isEmpty() == false) {
            Action action = pending.removeFirst();
            try {
                execute(action);
            } catch (Throwable failure) {
                primary = addFailure(primary, failure);
            }
        }
        return primary;
    }

    private void execute(Action action) throws Throwable {
        switch (action) {
            case ATTACH -> requireCallback(attachCallback, "attach").run();
            case DETACH -> requireCallback(detachCallback, "detach").run();
            case CLOSE -> closeOnce();
            case CLOSE_THEN_NAVIGATE -> closeThenNavigate();
            case NAVIGATE -> navigateOnce();
        }
    }

    private void closeThenNavigate() throws Throwable {
        Throwable primary = null;
        try {
            closeOnce();
        } catch (Throwable failure) {
            primary = failure;
        }
        try {
            navigateOnce();
        } catch (Throwable failure) {
            primary = addFailure(primary, failure);
        }
        if (primary != null) {
            throw primary;
        }
    }

    private void closeOnce() {
        if (closeAttempted) {
            return;
        }
        closeAttempted = true;
        terminalRequested = true;
        Runnable callback = closeCallback;
        attachCallback = null;
        detachCallback = null;
        closeCallback = null;
        requireCallback(callback, "close").run();
    }

    private void navigateOnce() {
        if (navigationAttempted) {
            return;
        }
        navigationAttempted = true;
        navigationRequested = false;
        Runnable callback = navigateCallback;
        navigateCallback = null;
        requireCallback(callback, "navigate").run();
    }

    private void enqueueDistinct(Action action) {
        if (pending.peekLast() != action) {
            pending.addLast(action);
        }
    }

    private void requireRequestContext() {
        requireOwnerThread();
        checkState(active, "Fabric Minecraft lifecycle requests require an active host transaction.");
    }

    private void requireOwnerThread() {
        checkState(Thread.currentThread() == ownerThread, "Fabric Minecraft lifecycle transactions are confined to their creator thread.");
    }

    private static Runnable requireCallback(Runnable callback, String name) {
        if (callback == null) {
            throw new IllegalStateException("Fabric Minecraft " + name + " callback was already released.");
        }
        return callback;
    }

    private static void checkState(boolean condition, String message) {
        if (condition == false) {
            throw new IllegalStateException(message);
        }
    }

    private static Throwable addFailure(Throwable primary, Throwable secondary) {
        if (secondary == null) {
            return primary;
        }
        if (primary == null) {
            return secondary;
        }
        FabricMinecraftFailures.addSuppressed(primary, secondary);
        return primary;
    }
}
