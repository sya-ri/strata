package dev.s7a.strata.projection

import dev.s7a.strata.component.DragScrollOptions

/**
 * Shared strict wire fields for viewport content-drag and inertia policy.
 */
public object ProjectionDragScrollOptions {
    /**
     * Encodes immutable drag options independently of client-local scroll geometry.
     */
    public fun encode(options: DragScrollOptions): ProjectionValue =
        ProjectionValue.Sequence(
            when (options) {
                DragScrollOptions.Disabled -> listOf(ProjectionValue.Flag(false))
                is DragScrollOptions.Enabled -> listOf(ProjectionValue.Flag(true), ProjectionValue.Real(options.dragThreshold), encodeInertia(options.inertia))
            },
        )

    /**
     * Decodes the complete policy and rejects malformed or out-of-range fields.
     */
    public fun decode(value: ProjectionValue): DragScrollOptions {
        val fields = ProjectionFields(value)
        val options =
            if (fields.flag()) {
                DragScrollOptions.Enabled(dragThreshold = fields.real(), inertia = decodeInertia(fields.value()))
            } else {
                DragScrollOptions.Disabled
            }
        fields.finish()
        return options
    }

    private fun encodeInertia(inertia: DragScrollOptions.Inertia): ProjectionValue =
        ProjectionValue.Sequence(
            when (inertia) {
                DragScrollOptions.Inertia.Disabled -> listOf(ProjectionValue.Flag(false))
                is DragScrollOptions.Inertia.Enabled -> listOf(ProjectionValue.Flag(true), ProjectionValue.Real(inertia.deceleration))
            },
        )

    private fun decodeInertia(value: ProjectionValue): DragScrollOptions.Inertia {
        val fields = ProjectionFields(value)
        val inertia = if (fields.flag()) DragScrollOptions.Inertia.Enabled(fields.real()) else DragScrollOptions.Inertia.Disabled
        fields.finish()
        return inertia
    }
}
