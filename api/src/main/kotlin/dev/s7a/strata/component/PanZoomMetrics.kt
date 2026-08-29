package dev.s7a.strata.component

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect

/**
 * Immutable position and resolved geometry snapshot for a pan-and-zoom viewport.
 *
 * [zoom] is a multiplier over the base [fit] scale, while [scale] is the resolved number of logical viewport pixels per content unit.
 * Before a viewport publishes geometry, [geometryKnown] is false and [scale] is only a finite placeholder that must not be used for coordinate conversion.
 *
 * @property center content coordinate shown at the viewport center.
 * @property zoom caller-controlled multiplier over the base fit scale.
 * @property scale resolved logical viewport pixels per content unit.
 * @property viewportSize current positive viewport extent when [geometryKnown] is true.
 * @property contentBounds current positive half-open content bounds when [geometryKnown] is true.
 * @property fit base scale policy.
 * @property geometryKnown whether a viewport has published usable geometry.
 * @throws IllegalArgumentException when numeric values are not finite and positive where required, or known geometry is empty.
 */
public data class PanZoomMetrics(
    public val center: DoubleOffset = DoubleOffset.Zero,
    public val zoom: Double = 1.0,
    public val scale: Double = 1.0,
    public val viewportSize: IntSize = IntSize.Zero,
    public val contentBounds: LongRect = LongRect.Zero,
    public val fit: PanZoomFit = PanZoomFit.Contain,
    public val geometryKnown: Boolean = false,
) {
    init {
        require(zoom.isFinite() && 0.0 < zoom) { "Pan-and-zoom multiplier must be finite and positive." }
        require(scale.isFinite() && 0.0 < scale) { "Pan-and-zoom scale must be finite and positive." }
        if (geometryKnown) {
            require(0 < viewportSize.width && 0 < viewportSize.height) { "Pan-and-zoom viewport size must be positive." }
            require(0L < contentBounds.width && 0L < contentBounds.height) { "Pan-and-zoom content bounds must be positive." }
        }
    }
}
