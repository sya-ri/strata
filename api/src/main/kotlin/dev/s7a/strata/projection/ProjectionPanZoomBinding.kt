package dev.s7a.strata.projection

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.geometry.DoubleOffset

/**
 * Shared transform endpoint for a viewport and its synchronous client navigation modifiers.
 * Only finite content coordinates and zoom values within the server state's immutable limits are accepted.
 */
internal object ProjectionPanZoomBinding {
    /**
     * Projects one caller-owned transform without exposing its geometry observer or source model.
     */
    fun project(
        scope: ProjectionScope,
        state: PanZoomState,
    ): ProjectionValue =
        scope.binding(
            ProjectionBinding(
                BuiltinProjection.PanZoom.type,
                state,
                { state.metrics.let { it.center to it.zoom } },
                { (center, zoom) -> ProjectionValue.Sequence(listOf(ProjectionValue.Real(center.x), ProjectionValue.Real(center.y), ProjectionValue.Real(zoom))) },
                { value ->
                    val fields = ProjectionFields(value)
                    val center = DoubleOffset(fields.real(), fields.real())
                    val zoom = fields.real()
                    fields.finish()
                    require(zoom in state.minimumZoom..state.maximumZoom) { "Pan-and-zoom input exceeds server limits." }
                    center to zoom
                },
                { (center, zoom) ->
                    state.zoomTo(zoom)
                    state.centerOn(center)
                },
            ),
        )
}
