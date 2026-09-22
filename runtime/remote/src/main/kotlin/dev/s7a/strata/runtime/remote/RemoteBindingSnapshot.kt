package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue

/**
 * Detached source identity, replacement generation, edit confirmation, value, and current input endpoint.
 */
public data class RemoteBindingSnapshot(
    public val identity: Long,
    public val generation: Long,
    public val acknowledged: Long,
    public val value: ProjectionValue,
    public val endpoint: Long,
) {
    init {
        require(0 < identity && 0 < generation) { "Invalid remote source identity or generation." }
        require(0 <= acknowledged && 0 < endpoint) { "Invalid remote source acknowledgement or endpoint." }
    }

    /**
     * Captures one edit against this exact source and replacement generation.
     */
    public fun edit(value: ProjectionValue): ProjectionValue =
        ProjectionValue.Sequence(
            listOf(ProjectionValue.Integer(identity), ProjectionValue.Integer(generation), value),
        )

    /**
     * Complete schema decoder for values emitted by a declaration projection scope.
     */
    public companion object {
        /**
         * Rejects incomplete records, invalid identities, and undeclared trailing fields.
         */
        public fun decode(value: ProjectionValue): RemoteBindingSnapshot {
            val fields = ProjectionFields(value)
            val snapshot = RemoteBindingSnapshot(fields.long(), fields.long(), fields.long(), fields.value(), fields.long())
            fields.finish()
            return snapshot
        }
    }
}
