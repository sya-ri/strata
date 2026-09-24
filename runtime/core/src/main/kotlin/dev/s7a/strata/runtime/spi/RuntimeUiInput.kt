package dev.s7a.strata.runtime.spi

import dev.s7a.strata.runtime.platform.currentThread
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiGameAction
import dev.s7a.strata.ui.UiInputPolicy

/**
 * Owner-thread forwarding gate for a fixed set of native bindings.
 * Tracks at most [bindings]' size, releases before changing owner/policy or entering text editing, and never forwards consumed UI events.
 * The adapter resolves actual remapped keys for each event and supplies only matching binding identities.
 */
@InternalStrataRuntimeApi
public class RuntimeUiInput<K : Any>(
    bindings: Map<K, UiGameAction>,
    private val press: (K) -> Unit,
    private val release: (K) -> Unit,
) : AutoCloseable {
    private val thread = currentThread()
    private val bindings = bindings.toMap()
    private val pressed = mutableSetOf<K>()
    private var owner: Any? = null
    private var policy = UiInputPolicy.BlockAll
    private var editing = false
    private var closed = false

    /**
     * Updates the active owner and permissions, clearing prior press ownership before adapter callbacks.
     */
    public fun configure(
        owner: Any?,
        policy: UiInputPolicy,
        editing: Boolean,
    ) {
        checkOwner()
        if (closed) return
        val enteringEditor = editing && this.editing.not()
        if (this.owner !== owner || this.policy != policy || enteringEditor) reset()
        this.owner = owner
        this.policy = policy
        this.editing = editing
    }

    /**
     * Forwards a physical key/button transition only to permitted bindings; release always clears a forwarded press.
     * Repeated down notifications do not toggle or enqueue another press while that binding remains held.
     */
    public fun event(
        matches: Collection<K>,
        down: Boolean,
        consumed: Boolean,
    ) {
        checkOwner()
        if (closed) return
        val forwarding = down && consumed.not() && editing.not()
        matches.forEach { key ->
            val action = bindings[key] ?: return@forEach
            if (forwarding && owner != null && policy.allows(action)) {
                if (pressed.add(key)) press(key)
            } else if (pressed.remove(key)) {
                release(key)
            }
        }
    }

    /**
     * Releases all forwarded inputs without changing configuration, including queued native clicks.
     */
    public fun reset() {
        checkOwner()
        val captured = pressed.toList()
        pressed.clear()
        var failure: Throwable? = null
        captured.forEach { key ->
            runCatching { release(key) }.exceptionOrNull()?.let { caught ->
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

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        owner = null
        reset()
    }

    private fun checkOwner() {
        check(currentThread() === thread) { "UI input requires its owner thread." }
    }
}
