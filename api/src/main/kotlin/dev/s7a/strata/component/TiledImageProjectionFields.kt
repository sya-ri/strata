package dev.s7a.strata.component

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.projection.ProjectionPanZoomBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionValue

/**
 * Shared positional geometry schema for the tiled viewport and its bounded tile layer.
 */
internal object TiledImageProjectionFields {
    /**
     * Encodes immutable source geometry and a shared transform binding after the server geometry cutoff.
     */
    fun encode(
        bounds: LongRect,
        levels: List<TiledImageLevel>,
        state: PanZoomState,
        size: IntSize,
        scope: ProjectionScope,
    ): List<ProjectionValue> =
        listOf(
            ProjectionValue.Sequence(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).map(ProjectionValue::Integer)),
            ProjectionValue.Sequence(levels.map { level -> ProjectionValue.Sequence(listOf(ProjectionValue.Integer(level.tilePixelSize.width.toLong()), ProjectionValue.Integer(level.tilePixelSize.height.toLong()), ProjectionValue.Integer(level.contentUnitsPerPixel))) }),
            ProjectionValue.Integer(size.width.toLong()),
            ProjectionValue.Integer(size.height.toLong()),
            ProjectionPanZoomBinding.project(scope, state),
            ProjectionValue.Real(state.minimumZoom),
            ProjectionValue.Real(state.maximumZoom),
        )
}
