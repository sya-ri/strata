package dev.s7a.strata.geometry

/**
 * An immutable half-open integer rectangle.
 *
 * @property left the inclusive left edge.
 * @property top the inclusive top edge.
 * @property right the exclusive right edge.
 * @property bottom the exclusive bottom edge.
 */
public data class IntRect(
    public val left: Int,
    public val top: Int,
    public val right: Int,
    public val bottom: Int,
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
    public val width: Int
        get() = right - left

    /**
     * The non-negative vertical extent.
     */
    public val height: Int
        get() = bottom - top

    /**
     * Returns whether a coordinate lies inside this half-open rectangle.
     *
     * @param point the coordinate to test.
     * @return true only for coordinates on or after the inclusive edges and before the exclusive edges.
     */
    public operator fun contains(point: IntOffset): Boolean = left <= point.x && point.x < right && top <= point.y && point.y < bottom

    /**
     * Translates this rectangle with checked coordinate arithmetic.
     *
     * @param offset the displacement.
     * @return the translated rectangle.
     */
    public operator fun plus(offset: IntOffset): IntRect =
        IntRect(
            Math.addExact(left, offset.x),
            Math.addExact(top, offset.y),
            Math.addExact(right, offset.x),
            Math.addExact(bottom, offset.y),
        )

    /**
     * The rectangle size.
     */
    public val size: IntSize
        get() = IntSize(width, height)
}
