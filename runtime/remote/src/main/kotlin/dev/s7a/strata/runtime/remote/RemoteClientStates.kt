@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner

/**
 * Execution-owner-confined presentation-state store bounded by the current decoded screen and its negotiated entry limit.
 * Keys combine a remote identity and a trusted type token; removed identities and terminal screens release their entries.
 * Preparation runs before declaration evaluation, so incoming values never mutate state from a render callback.
 * Editable membership uses the same keys and admission order, contains only current entries, and is cleared before release callbacks.
 * Every current editable entry is polled, including distinct keys sharing a value; membership never caches authoritative values.
 */
public class RemoteClientStates internal constructor(
    private val limits: RemoteLimits,
) : AutoCloseable {
    private val owner = RuntimeExecutionOwner.current()
    private val values = mutableMapOf<Key, Entry>()
    private val retained = mutableSetOf<Key>()
    private val editable = mutableMapOf<Key, RemoteEditableValue>()
    private var phase = Phase.Idle
    private var flushing = false
    private var preparingEntry = false

    /**
     * Creates or updates a typed presentation value from a registered preparation callback.
     * [release] runs exactly once when the value is retired, including terminal failure cleanup.
     * Creation and update callbacks cannot reenter preparation; closing from either callback invalidates this operation.
     * A fresh factory result returned after close is released instead of admitted; callback failures remain primary.
     */
    public fun <T : Any> prepare(
        identity: Long,
        key: RemoteStateKey<T>,
        create: () -> T,
        update: (T) -> Unit,
        release: (T) -> Unit = {},
    ) {
        checkOwner()
        check(phase == Phase.Preparing) { "Remote state can only be prepared before declaration evaluation." }
        require(0 < identity) { "Remote state identities must be positive." }
        check(preparingEntry.not()) { "Remote state preparation cannot reenter an entry callback." }
        preparingEntry = true
        try {
            val address = Key(identity, key)
            val entry =
                values[address] ?: run {
                    require(values.size < limits.collectionEntries) { "Too many retained remote values." }
                    val value = create()
                    if (phase != Phase.Preparing) {
                        release(value)
                        error("Remote state closed before a factory result could be admitted.")
                    }
                    Entry(value) { release(value) }.also {
                        values[address] = it
                        if (value is RemoteEditableValue) editable[address] = value
                    }
                }
            retained.add(address)
            update(key.type.java.cast(entry.value))
            check(phase == Phase.Preparing) { "Remote state closed during its update callback." }
        } finally {
            preparingEntry = false
        }
    }

    /**
     * Reads a value previously prepared for the same typed identity without creating or mutating it.
     */
    public fun <T : Any> get(
        identity: Long,
        key: RemoteStateKey<T>,
    ): T {
        checkOwner()
        check(phase == Phase.Idle) { "Remote state is not available for declaration evaluation." }
        return key.type.java.cast(values.getValue(Key(identity, key)).value)
    }

    /**
     * Flushes current editable values once; nested action allocation never recursively flushes the store.
     */
    public fun flushEdits(actions: RemoteClientActions) {
        checkOwner()
        check(phase == Phase.Idle) { "Remote edits require an active idle state store." }
        if (flushing) return
        flushing = true
        try {
            val iterator = editable.values.iterator()
            while (iterator.hasNext()) {
                iterator.next().flushEdits(actions)
                check(phase == Phase.Idle) { "Remote state closed during edit polling." }
            }
        } finally {
            flushing = false
        }
    }

    /**
     * Runs one complete preparation phase and retires presentation entries omitted by the next declaration.
     */
    internal fun update(prepare: () -> Unit) {
        checkOwner()
        check(phase == Phase.Idle && flushing.not()) { "Remote state preparation cannot reenter another store operation." }
        phase = Phase.Preparing
        retained.clear()
        try {
            prepare()
            check(phase == Phase.Preparing) { "Remote state closed during preparation." }
            phase = Phase.Retiring
            val obsolete = values.keys.filter { (it in retained).not() }
            obsolete.forEach { address ->
                val entry = values.remove(address)
                editable.remove(address)
                entry?.release?.invoke()
                check(phase == Phase.Retiring) { "Remote state closed during retirement." }
            }
        } finally {
            retained.clear()
            if (phase != Phase.Closed) phase = Phase.Idle
        }
    }

    override fun close() {
        checkOwner()
        if (phase == Phase.Closed) return
        phase = Phase.Closed
        val retiring = values.values.toList()
        values.clear()
        editable.clear()
        retained.clear()
        var failure: Throwable? = null
        retiring.forEach { entry ->
            runCatching(entry.release).exceptionOrNull()?.let { caught ->
                val primary = failure
                if (primary == null) {
                    failure = caught
                } else if (primary !== caught) {
                    primary.addSuppressed(caught)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() == owner) { "Remote presentation state belongs to another execution owner." }
    }

    private data class Key(
        val identity: Long,
        val type: RemoteStateKey<*>,
    )

    private class Entry(
        val value: Any,
        val release: () -> Unit,
    )

    private enum class Phase { Idle, Preparing, Retiring, Closed }
}
