package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Checks whether an original destination edge meets a physical pixel center at positive [scale].
 *
 * Native quad rasterizers can exclude a sample included by the portable half-open coverage contract.
 * The caller preserves those commands through exact fallback instead of biasing vertices or source interpolation.
 * This pure check retains no geometry or native state and is safe on any thread.
 */
@JvmSynthetic
internal fun FloatRect.hasFabricPhysicalCenterEdge(scale: Int): Boolean {
    fun center(edge: Float): Boolean {
        val physical = edge.toDouble() * scale
        return physical - floor(physical) == 0.5
    }
    return center(left) || center(top) || center(right) || center(bottom)
}

/**
 * Bounds fractional drawing inside an integer viewport without constructing an unbounded integer envelope.
 *
 * The returned rectangle controls portable texture allocation only; callers retain the original floating-point destination for localization and sampling.
 * Intersecting exact floating-point edges with the viewport before rounding avoids integer-extent overflow for finite destinations spanning the integer coordinate range.
 * The immutable inputs remain unchanged, are never retained, and may be shared between threads.
 *
 * @param viewport the drawable integer bounds in the same coordinate space as the receiver.
 * @return a nonempty integer envelope contained in [viewport], or null when the destination and viewport have no positive-area intersection.
 */
@JvmSynthetic
internal fun FloatRect.enclosingFabricViewportBounds(viewport: IntRect): IntRect? {
    val visibleLeft = maxOf(left.toDouble(), viewport.left.toDouble())
    val visibleTop = maxOf(top.toDouble(), viewport.top.toDouble())
    val visibleRight = minOf(right.toDouble(), viewport.right.toDouble())
    val visibleBottom = minOf(bottom.toDouble(), viewport.bottom.toDouble())
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null
    return IntRect(
        floor(visibleLeft).toInt(),
        floor(visibleTop).toInt(),
        ceil(visibleRight).toInt(),
        ceil(visibleBottom).toInt(),
    )
}
