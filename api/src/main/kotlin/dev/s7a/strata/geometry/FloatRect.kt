package dev.s7a.strata.geometry

/**
 * An immutable, thread-safe half-open rectangle with finite floating-point edges and extents.
 *
 * Empty rectangles are valid geometry; individual drawing operations may require positive extents.
 * Construction rejects unordered edges, non-finite coordinates, and extents that overflow a [Float].
 *
 * @property left the inclusive left edge.
 * @property top the inclusive top edge.
 * @property right the exclusive right edge.
 * @property bottom the exclusive bottom edge.
 * @throws IllegalArgumentException when an edge or extent is non-finite or the edges are unordered.
 */
public data class FloatRect(
    public val left: Float,
    public val top: Float,
    public val right: Float,
    public val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Rectangle edges must be finite."
        }
        require(left <= right) { "Right must not be less than left." }
        require(top <= bottom) { "Bottom must not be less than top." }
        require((right - left).isFinite() && (bottom - top).isFinite()) { "Rectangle extents must be finite." }
    }

    /**
     * The finite, non-negative horizontal extent.
     */
    public val width: Float
        get() = right - left

    /**
     * The finite, non-negative vertical extent.
     */
    public val height: Float
        get() = bottom - top

    /**
     * Returns a translated rectangle using floating-point coordinate arithmetic.
     *
     * The receiver and [offset] remain unchanged and can be shared between threads.
     *
     * @param offset the integer displacement in the same coordinate space.
     * @return a new rectangle with the displacement added to every edge.
     * @throws IllegalArgumentException when the translated edges or extents are non-finite.
     */
    public operator fun plus(offset: IntOffset): FloatRect = FloatRect(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y)
}
