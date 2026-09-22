package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import java.util.IdentityHashMap

/**
 * Current-declaration-bound value identities and edit confirmations owned by one server session.
 * Authoritative values stay in caller-owned bindings; only the previous projected value is retained here.
 * A changed value outside an accepted input advances its replacement generation.
 */
internal class RemoteServerBindings(
    private val limits: RemoteLimits,
    private val sequence: () -> Long,
) : AutoCloseable {
    private var current = IdentityHashMap<Any, Value>()
    private var pending = IdentityHashMap<Any, Value>()
    private var nextIdentity = 1L

    /**
     * Begins a new declaration cutoff without retaining sources absent from the next completed projection.
     */
    fun begin() {
        pending.clear()
    }

    /**
     * Projects one typed source and registers its generation-checked input endpoint.
     */
    fun <T : Any> project(
        binding: ProjectionBinding<T>,
        scope: ProjectionScope,
    ): ProjectionValue {
        val projected = binding.encode(binding.read())
        val retained = pending[binding.identity] ?: current[binding.identity] ?: create(binding.type, projected)
        require(retained.type == binding.type) { "A state source cannot use conflicting remote schemas." }
        if (retained.value != projected) {
            check(retained.generation < Long.MAX_VALUE) { "Remote value generation space is exhausted." }
            retained.generation += 1
            retained.acknowledged = 0
            retained.value = projected
        }
        pending[binding.identity] = retained
        val endpoint =
            scope.action(
                ProjectionAction(binding.type, { decode(binding, it) }) { edit ->
                    if (edit.identity == retained.identity && edit.generation == retained.generation) {
                        binding.write(edit.value)
                        retained.value = binding.encode(binding.read())
                        retained.acknowledged = sequence()
                    }
                },
            )
        return ProjectionValue.Sequence(
            listOf(
                ProjectionValue.Integer(retained.identity),
                ProjectionValue.Integer(retained.generation),
                ProjectionValue.Integer(retained.acknowledged),
                retained.value,
                ProjectionValue.Integer(endpoint),
            ),
        )
    }

    /**
     * Releases identities and previous values that are no longer reachable from the committed declaration.
     */
    fun commit() {
        current = pending
        pending = IdentityHashMap()
    }

    override fun close() {
        current.clear()
        pending.clear()
    }

    private fun create(
        type: ProjectionType,
        value: ProjectionValue,
    ): Value {
        require(pending.size < limits.collectionEntries) { "Too many remote state bindings." }
        check(nextIdentity < Long.MAX_VALUE) { "Remote source identity space is exhausted." }
        return Value(nextIdentity++, type, value)
    }

    private fun <T : Any> decode(
        binding: ProjectionBinding<T>,
        value: ProjectionValue,
    ): Edit<T> {
        val fields = ProjectionFields(value)
        val identity = fields.long()
        val generation = fields.long()
        require(0 < identity && 0 < generation) { "Invalid remote edit identity or generation." }
        val result = Edit(identity, generation, binding.decode(fields.value()))
        fields.finish()
        return result
    }

    /**
     * One projected value's ordering metadata; owns no handler or retained node.
     */
    private class Value(
        val identity: Long,
        val type: ProjectionType,
        var value: ProjectionValue,
    ) {
        var generation = 1L
        var acknowledged = 0L
    }

    private data class Edit<T : Any>(
        val identity: Long,
        val generation: Long,
        val value: T,
    )
}
