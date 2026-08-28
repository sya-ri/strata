package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Immutable logical viewport and pixel scale metadata for one showcase render.
 *
 * The value owns no rendering resource and may be shared across threads.
 * Physical dimensions are computed once with checked integer multiplication.
 *
 * @property size positive logical dimensions of the complete frame.
 * @property scale positive number of physical pixels per logical pixel.
 * @throws IllegalArgumentException when a logical dimension or [scale] is not positive.
 * @throws ArithmeticException when either physical dimension exceeds the integer range.
 */
internal class ShowcaseViewport internal constructor(
    internal val size: IntSize,
    internal val scale: Int,
) {
    init {
        require(0 < size.width && 0 < size.height && 0 < scale) { "Showcase viewport dimensions and GUI scale must be positive." }
    }

    /**
     * Exact physical dimensions expected in a freshly rasterized PNG, without image upscaling.
     */
    internal val physicalSize: IntSize = IntSize(Math.multiplyExact(size.width, scale), Math.multiplyExact(size.height, scale))
}
