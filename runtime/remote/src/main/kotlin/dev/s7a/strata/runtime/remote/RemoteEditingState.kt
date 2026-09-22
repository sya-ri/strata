package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue

/**
 * One stable native editing state with optimistic value ordering shared by standard and custom controls.
 * The enclosing [RemoteClientStates] owns its lifetime and calls updates outside declaration evaluation.
 * Unacknowledged values and unchanged confirmations never invoke [write], preserving native selection and composition.
 */
public class RemoteEditingState<S : Any, T : Any>(
    public val state: S,
    private val type: ProjectionType,
    private var snapshot: RemoteBindingSnapshot,
    private val decode: (ProjectionValue) -> T,
    private val encode: (T) -> ProjectionValue,
    private val read: (S) -> T,
    private val write: (S, T) -> Unit,
) : RemoteEditableValue {
    private val buffer = RemoteEditBuffer(decode(snapshot.value), snapshot.generation)
    private var sending = false

    /**
     * Applies a confirmed server value without replacing the caller's native state instance.
     */
    public fun reconcile(snapshot: RemoteBindingSnapshot) {
        require(snapshot.identity == this.snapshot.identity) { "Remote editing state cannot change its source identity." }
        val decoded = decode(snapshot.value)
        if (buffer.reconcile(decoded, snapshot.generation, snapshot.acknowledged)) write(state, buffer.value)
        if (this.snapshot.generation <= snapshot.generation) this.snapshot = snapshot
    }

    override fun flushEdits(actions: RemoteClientActions) {
        if (sending) return
        val value = read(state)
        if (value == buffer.value) return
        sending = true
        try {
            val sequence = actions.send(snapshot.endpoint, type, snapshot.edit(encode(value)))
            buffer.edit(value, sequence)
        } finally {
            sending = false
        }
    }
}
