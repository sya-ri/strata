package dev.s7a.strata.runtime.spi

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.ui.UiGameAction
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiSession

/**
 * Execution-owner-confined forwarding gate for a fixed set of native bindings.
 * Tracks at most [bindings]' size, releases before changing input session/policy or entering text editing, and never forwards consumed UI events.
 * The adapter resolves actual remapped keys for each event and supplies only matching binding identities.
 */
@InternalStrataRuntimeApi
public class RuntimeUiInput<K : Any>(
    bindings: Map<K, UiGameAction>,
    private val press: (K) -> Unit,
    private val release: (K) -> Unit,
) : AutoCloseable {
    private val owner = RuntimeExecutionOwner.current()
    private val bindings = bindings.toMap()
    private val pressed = mutableSetOf<K>()
    private var inputSession: UiSession? = null
    private var policy = UiInputPolicy.BlockAll
    private var editing = false
    private var closed = false

    /**
     * Updates the active input session and permissions, clearing prior press ownership before adapter callbacks.
     */
    public fun configure(
        inputSession: UiSession?,
        policy: UiInputPolicy,
        editing: Boolean,
    ) {
        checkOwner()
        if (closed) return
        val enteringEditor = editing && this.editing.not()
        if (this.inputSession !== inputSession || this.policy != policy || enteringEditor) reset()
        this.inputSession = inputSession
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
            if (forwarding && inputSession != null && policy.allows(action)) {
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
        inputSession = null
        reset()
    }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() == owner) { "UI input requires its execution owner." }
    }
}
