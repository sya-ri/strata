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
            listOf(
                ProjectionValue.Flag(options.enabled),
                ProjectionValue.Flag(options.inertiaEnabled),
                ProjectionValue.Real(options.dragThreshold),
                ProjectionValue.Real(options.deceleration),
            ),
        )

    /**
     * Decodes the complete policy and rejects malformed or out-of-range fields.
     */
    public fun decode(value: ProjectionValue): DragScrollOptions {
        val fields = ProjectionFields(value)
        val options = DragScrollOptions(fields.flag(), fields.flag(), fields.real(), fields.real())
        fields.finish()
        return options
    }
}
