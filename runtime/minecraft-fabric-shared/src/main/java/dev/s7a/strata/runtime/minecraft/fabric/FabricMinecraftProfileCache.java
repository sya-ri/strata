package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility;
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Retains at most one immutable value for the active resource-manager identity and font selections.
 *
 * <p>Extraction and reuse belong to the client thread. Invalidation may arrive from any thread and ignores unrelated resource managers, including integrated-server managers. The empty state retains only a generation counter and terminal flag. Replacement, failure, reload, and close drop the previous entry without retaining its value or supplier. Values own no native resources and remain valid for their independent consumers after invalidation.</p>
 *
 * @param <V> detached immutable profile or snapshot value.
 */
final class FabricMinecraftProfileCache<V> {
    private final AtomicReference<State<V>> current = new AtomicReference<>(new State<>(0L, null, false));
    private final AtomicBoolean extracting = new AtomicBoolean();

    /**
     * Reuses the current value or synchronously extracts one replacement on the owning client thread.
     *
     * <p>The caller validates the Minecraft client thread before entry. The captured state fences both the initial claim and publication against reload or terminal invalidation, including when the cache was empty. An unsuccessful replacement leaves no cached value; failures are never substituted with an older generation.</p>
     *
     * @param manager active native resource manager, compared by identity only.
     * @param compatibility immutable compiler-selected font capabilities.
     * @param options immutable current font and language selections.
     * @param extract callback borrowed for this invocation only.
     * @return the shared immutable current value.
     * @throws IllegalStateException on reentrant extraction, cross-thread reuse, or invalidation during extraction.
     * @throws Throwable when extraction fails; the exact failure propagates after pending ownership is cleared.
     */
    V get(
            Object manager,
            MinecraftFontCompatibility compatibility,
            MinecraftFontOptions options,
            Supplier<? extends V> extract) {
        return get(manager, compatibility, options, extract, () -> { });
    }

    /**
     * Borrows a deterministic test barrier after state capture and before claiming a cold extraction.
     *
     * <p>The ordinary overload supplies a no-op barrier. Neither callback is retained, and the barrier is skipped on cache hits. The loading flag remains set through invalidation until this invocation unwinds, so canceled work cannot reenter an apparently empty cache.</p>
     *
     * @param manager active resource manager, compared by identity only.
     * @param compatibility immutable compiler-selected font capabilities.
     * @param options immutable current font and language selections.
     * @param extract callback borrowed for this invocation only.
     * @param beforeClaim borrowed callback for deterministic lifecycle race tests.
     * @return shared immutable current value.
     * @throws IllegalStateException on terminal access, reentry, concurrent extraction, or generation changes.
     */
    V get(
            Object manager,
            MinecraftFontCompatibility compatibility,
            MinecraftFontOptions options,
            Supplier<? extends V> extract,
            Runnable beforeClaim) {
        State<V> initial = current.get();
        if (initial.terminal) {
            throw new IllegalStateException("Minecraft UI profiles cannot reopen after client resource shutdown.");
        }
        if (extracting.get()) {
            throw new IllegalStateException("Minecraft UI profile extraction must not be reentrant or concurrent.");
        }
        Entry<V> previous = initial.entry;
        if (previous != null) {
            previous.requireOwner();
            if (previous.matches(manager, compatibility, options)) {
                if (current.get() != initial) {
                    throw new IllegalStateException("Minecraft UI resources changed before profile reuse.");
                }
                return previous.value;
            }
        }
        long epoch = initial.epoch;
        State<V> pending = new State<>(epoch, new Entry<>(manager, compatibility, options, null), false);
        if (extracting.compareAndSet(false, true) == false) {
            throw new IllegalStateException("Minecraft UI profile extraction must not be concurrent.");
        }
        try {
            beforeClaim.run();
            if (current.compareAndSet(initial, pending) == false) {
                throw new IllegalStateException("Minecraft UI resources changed before profile extraction.");
            }
            V value = Objects.requireNonNull(extract.get(), "Minecraft UI profile extraction returned null.");
            State<V> ready = new State<>(epoch, new Entry<>(manager, compatibility, options, value), false);
            if (current.compareAndSet(pending, ready) == false) {
                throw new IllegalStateException("Minecraft UI resources changed during profile extraction.");
            }
            return value;
        } finally {
            current.compareAndSet(pending, new State<>(epoch, null, false));
            extracting.set(false);
        }
    }

    /**
     * Advances the generation before the active client's reload, or evicts a matching test-owned manager.
     *
     * <p>The active client's reload advances the counter even while empty, fencing a claim which already captured the preceding state. Other managers can only evict their own populated entry. The native bridge supplies active-client identity without retaining it in an empty cache. This operation is thread-safe and does not close values already held by screens.</p>
     *
     * @param manager native resource manager beginning reload.
     * @param activeClient whether the caller verified the current Minecraft client owns this manager.
     */
    void invalidate(Object manager, boolean activeClient) {
        transition(manager, activeClient, false);
    }

    /**
     * Permanently releases the active client's cache at native shutdown; unrelated manager close cannot terminate it.
     *
     * <p>Existing immutable consumers remain valid. A terminal cache rejects every subsequent get and retains no manager, profile, supplier, or historical generation. A matching owned-manager close only evicts that test-owned entry.</p>
     *
     * @param manager native resource manager beginning close.
     * @param activeClient whether the caller verified the current Minecraft client owns this manager.
     */
    void close(Object manager, boolean activeClient) {
        transition(manager, activeClient, activeClient);
    }

    private void transition(Object manager, boolean activeClient, boolean terminal) {
        State<V> observed = current.get();
        while (observed.terminal == false) {
            Entry<V> entry = observed.entry;
            if (activeClient == false && (entry == null || entry.manager != manager)) {
                return;
            }
            State<V> replacement = new State<>(Math.incrementExact(observed.epoch), null, terminal);
            if (current.compareAndSet(observed, replacement)) {
                return;
            }
            observed = current.get();
        }
    }

    private static final class State<V> {
        private final long epoch;
        private final Entry<V> entry;
        private final boolean terminal;

        private State(long epoch, Entry<V> entry, boolean terminal) {
            this.epoch = epoch;
            this.entry = entry;
            this.terminal = terminal;
        }
    }

    private static final class Entry<V> {
        private final Object manager;
        private final MinecraftFontCompatibility compatibility;
        private final MinecraftFontOptions options;
        private final Thread owner = Thread.currentThread();
        private final V value;

        private Entry(Object manager, MinecraftFontCompatibility compatibility, MinecraftFontOptions options, V value) {
            this.manager = Objects.requireNonNull(manager);
            this.compatibility = Objects.requireNonNull(compatibility);
            this.options = Objects.requireNonNull(options);
            this.value = value;
        }

        private boolean matches(Object candidate, MinecraftFontCompatibility capabilities, MinecraftFontOptions selections) {
            return manager == candidate && compatibility.equals(capabilities) && options.equals(selections);
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Minecraft UI profiles must be reused on their client thread.");
            }
        }
    }
}
