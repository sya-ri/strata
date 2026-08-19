package dev.s7a.strata.geometry

/**
 * Inclusive minimum and maximum size constraints.
 *
 * `Int.MAX_VALUE` denotes an unbounded maximum. Every minimum is non-negative and cannot exceed its maximum.
 *
 * @property minWidth the minimum width.
 * @property maxWidth the maximum width, or [Int.MAX_VALUE] for an unbounded width.
 * @property minHeight the minimum height.
 * @property maxHeight the maximum height, or [Int.MAX_VALUE] for an unbounded height.
 */
public data class Constraints(
    public val minWidth: Int = 0,
    public val maxWidth: Int = Int.MAX_VALUE,
    public val minHeight: Int = 0,
    public val maxHeight: Int = Int.MAX_VALUE,
) {
    init {
        require(0 <= minWidth) { "Minimum width must be non-negative." }
        require(0 <= minHeight) { "Minimum height must be non-negative." }
        require(0 <= maxWidth) { "Maximum width must be non-negative." }
        require(0 <= maxHeight) { "Maximum height must be non-negative." }
        require(minWidth <= maxWidth) { "Minimum width must not exceed maximum width." }
        require(minHeight <= maxHeight) { "Minimum height must not exceed maximum height." }
    }

    /**
     * Clamps a size to these constraints.
     *
     * @param size the candidate size.
     * @return the constrained size.
     */
    public fun constrain(size: IntSize): IntSize =
        IntSize(
            size.width.coerceIn(minWidth, maxWidth),
            size.height.coerceIn(minHeight, maxHeight),
        )

    /**
     * Returns whether a size already satisfies these constraints.
     *
     * @param size the candidate size.
     * @return true when both extents lie within the inclusive ranges.
     */
    public fun isSatisfiedBy(size: IntSize): Boolean =
        minWidth <= size.width && size.width <= maxWidth &&
            minHeight <= size.height && size.height <= maxHeight

    /**
     * Provides constructors for common constraint sets.
     */
    public companion object {
        /**
         * Creates fixed constraints for one exact width and height.
         *
         * @param width the non-negative fixed width.
         * @param height the non-negative fixed height.
         * @return constraints whose minimums and maximums equal the arguments.
         */
        public fun fixed(
            width: Int,
            height: Int,
        ): Constraints = Constraints(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height)
    }
}
