package dev.s7a.strata.geometry

import kotlin.math.floor

/**
 * Reports whether every edge can cross the public double-coordinate transform without rounding.
 *
 * @receiver integer rectangle considered for pan-and-zoom geometry.
 * @return true only when converting each edge to [Double] and back preserves its exact [Long] value.
 */
internal fun LongRect.hasExactlyRepresentableDoubleEdges(): Boolean =
    left.isExactlyRepresentableAsDouble() &&
        top.isExactlyRepresentableAsDouble() &&
        right.isExactlyRepresentableAsDouble() &&
        bottom.isExactlyRepresentableAsDouble()

/**
 * Converts the mathematical midpoint of both axes without adding edges or rounding an odd extent first.
 *
 * This pure conversion retains no state, may run on any thread, and returns null rather than exposing rounded coordinates.
 *
 * @receiver integer rectangle considered for pan-and-zoom geometry.
 * @return the exact double-coordinate midpoint, or null when either half-integer or integer midpoint is not exactly representable.
 */
internal fun LongRect.exactDoubleCenterOrNull(): DoubleOffset? {
    val centerX = exactDoubleMidpointOrNull(left, right) ?: return null
    val centerY = exactDoubleMidpointOrNull(top, bottom) ?: return null
    return DoubleOffset(centerX, centerY)
}

/**
 * Converts one mathematical midpoint without adding the inputs or rounding an odd difference first.
 *
 * This pure conversion retains no state, may run on any thread, and handles every pair of [Long] inputs without arithmetic overflow.
 *
 * @param first first integer edge.
 * @param second second integer edge.
 * @return the exact double-coordinate midpoint, or null when it cannot be represented without rounding.
 */
internal fun exactDoubleMidpointOrNull(
    first: Long,
    second: Long,
): Double? {
    val quotient = Math.addExact(Math.floorDiv(first, 2L), Math.floorDiv(second, 2L))
    val remainder = Math.addExact(Math.floorMod(first, 2L), Math.floorMod(second, 2L))
    val whole = if (remainder == 2L) Math.incrementExact(quotient) else quotient
    if (remainder != 1L) return whole.takeIf(Long::isExactlyRepresentableAsDouble)?.toDouble()
    val candidate = whole.toDouble() + 0.5
    val candidateFloor = floor(candidate)
    return candidate.takeIf { value -> value - candidateFloor == 0.5 && candidateFloor.toLong() == whole }
}

private fun Long.isExactlyRepresentableAsDouble(): Boolean = this != Long.MAX_VALUE && toDouble().toLong() == this
