package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import kotlin.math.ceil

/**
 * Stateless clip conversion shared by portable primitives; it retains no raster or command references.
 */
internal object RasterClips {
    /**
     * Finds logical cells touched by an already viewport-bounded physical clip at positive [scale].
     * The enclosing cells preserve logical source sampling while pixel writes obey the original clip.
     */
    fun logical(
        physical: IntRect,
        scale: Int,
    ): IntRect =
        IntRect(
            physical.left / scale,
            physical.top / scale,
            ((physical.right.toLong() + scale - 1L) / scale).toInt(),
            ((physical.bottom.toLong() + scale - 1L) / scale).toInt(),
        )

    /**
     * Resolves half-open fractional edges against physical pixel centers at positive [scale].
     * Clamp to the nonnegative [size] before integer conversion, including distant offscreen edges.
     */
    fun physical(
        bounds: FloatRect,
        size: IntSize,
        scale: Int,
    ): IntRect {
        fun edge(
            value: Float,
            extent: Int,
        ): Int = ceil(value.toDouble() * scale - 0.5).coerceIn(0.0, extent.toDouble()).toInt()
        return IntRect(edge(bounds.left, size.width), edge(bounds.top, size.height), edge(bounds.right, size.width), edge(bounds.bottom, size.height))
    }
}
