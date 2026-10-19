package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Retains one detached immutable value for the resource-manager identity and current font selections.
 * Extraction and reuse belong to the client owner; invalidation may arrive from any thread and ignores unrelated managers.
 * Empty generations retain no manager, value or callback, and terminal close permanently rejects later extraction.
 */
internal class FabricMinecraftProfileCache<V : Any> {
    private val current = AtomicReference(State<V>(null, terminal = false))
    private val extracting = AtomicBoolean()

    /**
     * Reuses an equal owner-thread value or synchronously extracts a replacement, preserving original extraction failures.
     * Captured state fences claim and publication against reload and shutdown, including when the cache was initially empty.
     * The caller validates the client thread; the optional test barrier and extraction callback are borrowed only until return.
     */
    fun get(
        manager: Any,
        compatibility: MinecraftFontCompatibility,
        options: MinecraftFontOptions,
        beforeClaim: () -> Unit = {},
        extract: () -> V,
    ): V {
        val initial = current.get()
        check(initial.terminal.not()) { "Minecraft UI profiles cannot reopen after client resource shutdown." }
        check(extracting.get().not()) { "Minecraft UI profile extraction must not be reentrant or concurrent." }
        val previous = initial.entry
        if (previous != null) {
            previous.requireOwner()
            if (previous.matches(manager, compatibility, options)) {
                check(current.get() === initial) { "Minecraft UI resources changed before profile reuse." }
                return checkNotNull(previous.value)
            }
        }
        val pending = State(Entry<V>(manager, compatibility, options, null), terminal = false)
        check(extracting.compareAndSet(false, true)) { "Minecraft UI profile extraction must not be concurrent." }
        try {
            beforeClaim()
            check(current.compareAndSet(initial, pending)) { "Minecraft UI resources changed before profile extraction." }
            val value = extract()
            val ready = State(Entry(manager, compatibility, options, value), terminal = false)
            check(current.compareAndSet(pending, ready)) { "Minecraft UI resources changed during profile extraction." }
            return value
        } finally {
            current.compareAndSet(pending, State(null, terminal = false))
            extracting.set(false)
        }
    }

    /**
     * Advances the active client's resource generation, or evicts only an unrelated manager's matching populated entry.
     * Empty active-client generations still advance to fence a claim that already captured their preceding state.
     */
    fun invalidate(
        manager: Any,
        activeClient: Boolean,
    ) {
        transition(manager, activeClient, terminal = false)
    }

    /**
     * Permanently releases the active client's cache; a matching unrelated manager only evicts its own entry.
     * Already detached values remain valid for their independent consumers.
     */
    fun close(
        manager: Any,
        activeClient: Boolean,
    ) {
        transition(manager, activeClient, terminal = activeClient)
    }

    private fun transition(
        manager: Any,
        activeClient: Boolean,
        terminal: Boolean,
    ) {
        var observed = current.get()
        while (observed.terminal.not()) {
            val entry = observed.entry
            if (activeClient.not() && (entry == null || entry.manager !== manager)) return
            val replacement = State<V>(null, terminal)
            if (current.compareAndSet(observed, replacement)) return
            observed = current.get()
        }
    }

    private class State<V>(
        val entry: Entry<V>?,
        val terminal: Boolean,
    )

    private class Entry<V>(
        val manager: Any,
        private val compatibility: MinecraftFontCompatibility,
        private val options: MinecraftFontOptions,
        val value: V?,
    ) {
        private val owner = Thread.currentThread()

        fun matches(
            candidate: Any,
            capabilities: MinecraftFontCompatibility,
            selections: MinecraftFontOptions,
        ): Boolean = manager === candidate && compatibility == capabilities && options == selections

        fun requireOwner() {
            check(Thread.currentThread() === owner) { "Minecraft UI profiles must be reused on their client thread." }
        }
    }
}
