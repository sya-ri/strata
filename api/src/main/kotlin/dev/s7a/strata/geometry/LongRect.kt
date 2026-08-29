package dev.s7a.strata.geometry

/**
 * An immutable half-open rectangle in a large integer content coordinate space.
 *
 * Construction validates edge ordering and rejects extents that overflow a [Long].
 * Empty rectangles are valid geometry, although components may require positive content extents.
 * The value owns no external resources and is safe to share between threads.
 *
 * @property left inclusive horizontal edge.
 * @property top inclusive vertical edge.
 * @property right exclusive horizontal edge.
 * @property bottom exclusive vertical edge.
 * @throws IllegalArgumentException when the edges are unordered.
 * @throws ArithmeticException when an extent cannot be represented as a [Long].
 */
public data class LongRect(
    public val left: Long,
    public val top: Long,
    public val right: Long,
    public val bottom: Long,
) {
    init {
        require(left <= right) { "Right must not be less than left." }
        require(top <= bottom) { "Bottom must not be less than top." }
        Math.subtractExact(right, left)
        Math.subtractExact(bottom, top)
    }

    /**
     * The non-negative horizontal extent.
     */
    public val width: Long
        get() = right - left

    /**
     * The non-negative vertical extent.
     */
    public val height: Long
        get() = bottom - top

    /**
     * Returns whether a finite content coordinate lies inside this rectangle.
     *
     * @param point coordinate to test.
     * @return true only between the inclusive and exclusive edges.
     */
    public operator fun contains(point: DoubleOffset): Boolean {
        val horizontal = 0 <= compareDoubleToLong(point.x, left) && compareDoubleToLong(point.x, right) < 0
        val vertical = 0 <= compareDoubleToLong(point.y, top) && compareDoubleToLong(point.y, bottom) < 0
        return horizontal && vertical
    }

    /**
     * Common rectangle constants.
     */
    public companion object {
        /**
         * The empty rectangle at the origin.
         */
        public val Zero: LongRect = LongRect(0L, 0L, 0L, 0L)

        private fun compareDoubleToLong(
            value: Double,
            boundary: Long,
        ): Int {
            if (value < Long.MIN_VALUE.toDouble()) return -1
            if (Long.MAX_VALUE.toDouble() <= value) return 1
            val integer = value.toLong()
            if (integer < boundary) return -1
            if (boundary < integer) return 1
            if (value == integer.toDouble()) return 0
            return if (value < 0.0) -1 else 1
        }
    }
}
