package dev.s7a.strata.projection

import dev.s7a.strata.component.ScrollState

/**
 * Shared scroll binding schema used by viewports and their independently placed scrollbar references.
 * Geometry stays client-local; current offsets and explicit server navigation use ordinary binding generations.
 */
public object ProjectionScrollBinding {
    /**
     * Binds a non-negative finite offset and optionally reports accepted displacement to a viewport's boundary-demand policy.
     * The callback runs in the server input boundary after the state accepted the offset.
     */
    public fun project(
        scope: ProjectionScope,
        state: ScrollState,
        moved: (Double) -> Unit = {},
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(BuiltinProjection.ScrollPosition.type, state, { state.metrics.offset }, ProjectionValue::Real, {
                requireNotNull(it as? ProjectionValue.Real).value.also { value -> require(0.0 <= value) }
            }) { value ->
                val previous = state.metrics.offset
                state.scrollTo(value)
                moved(value - previous)
            },
        )
}
