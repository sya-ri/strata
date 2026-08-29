package dev.s7a.strata.geometry

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

private fun Long.isExactlyRepresentableAsDouble(): Boolean = this != Long.MAX_VALUE && toDouble().toLong() == this
