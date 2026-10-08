package dev.s7a.strata.integration.minecraft.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Captures borrowed fixture settings without mutation and applies them inside the caller's protected lifetime.
 * The owner is checked before every capture, application and close, including an already closed lease.
 * Every captured setting is restored and read back once after application starts, including setters that mutate and then fail.
 * This package-private fixture helper uses no game API; the integration owner supplies its thread guard.
 */
final class MinecraftNativePerformanceOptionLease implements AutoCloseable {
    private enum State { Capturing, Applied, Closed }

    private final Runnable checkOwner;
    private final List<Runnable> applications = new ArrayList<>();
    private final List<Runnable> restorations = new ArrayList<>();
    private State state = State.Capturing;

    /** Creates one non-mutating client-owned lease; no setting is read before its owner guard. */
    MinecraftNativePerformanceOptionLease(Runnable checkOwner) {
        checkOwner.run();
        this.checkOwner = checkOwner;
    }

    /** Captures the current value and both bounded actions before any application may start. */
    <T> void capture(Supplier<T> read, Consumer<T> write, T borrowed) {
        checkOwner.run();
        if (state != State.Capturing) {
            throw new IllegalStateException("Fixture options must be captured before application.");
        }
        T previous = read.get();
        applications.add(() -> write.accept(borrowed));
        restorations.add(() -> {
            write.accept(previous);
            if (Objects.equals(read.get(), previous) == false) {
                throw new IllegalStateException("A borrowed native fixture option was not restored.");
            }
        });
    }

    /** Applies captured settings once; the caller must close this lease after any returned or thrown result. */
    void apply() {
        checkOwner.run();
        if (state != State.Capturing) {
            throw new IllegalStateException("Fixture options can only be applied once.");
        }
        state = State.Applied;
        try {
            for (Runnable application : applications) {
                application.run();
            }
        } finally {
            applications.clear();
        }
    }

    /** Restores every independent setting once, preserving the first failure and suppressing later failures. */
    @Override
    public void close() {
        checkOwner.run();
        if (state == State.Closed) {
            return;
        }
        boolean applied = state == State.Applied;
        state = State.Closed;
        Throwable failure = null;
        try {
            if (applied) {
                for (Runnable restoration : restorations) {
                    try {
                        restoration.run();
                    } catch (Throwable caught) {
                        if (failure == null) {
                            failure = caught;
                        } else if (failure != caught) {
                            failure.addSuppressed(caught);
                        }
                    }
                }
            }
        } finally {
            applications.clear();
            restorations.clear();
        }
        if (failure != null) {
            throw unchecked(failure);
        }
    }

    // Kotlin and native callbacks can propagate checked failures through Java functional interfaces.
    // Preserve that exact first cause after every restoration has been attempted.
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException unchecked(Throwable failure) throws T {
        throw (T) failure;
    }
}
