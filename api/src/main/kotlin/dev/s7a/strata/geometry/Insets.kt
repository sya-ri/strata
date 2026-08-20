package dev.s7a.strata.geometry

/**
 * Immutable non-negative distances applied to the four sides of a rectangle.
 *
 * Horizontal and vertical totals are checked during construction.
 * Every constructed instance can therefore be used by layout arithmetic without silently wrapping an integer.
 *
 * @property left the distance on the left side.
 * @property top the distance on the top side.
 * @property right the distance on the right side.
 * @property bottom the distance on the bottom side.
 * @throws IllegalArgumentException when a side is negative.
 * @throws ArithmeticException when either axis total cannot be represented as an [Int].
 */
public data class Insets(
    public val left: Int = 0,
    public val top: Int = 0,
    public val right: Int = 0,
    public val bottom: Int = 0,
) {
    init {
        require(0 <= left) { "Left inset must be non-negative." }
        require(0 <= top) { "Top inset must be non-negative." }
        require(0 <= right) { "Right inset must be non-negative." }
        require(0 <= bottom) { "Bottom inset must be non-negative." }
        Math.addExact(left, right)
        Math.addExact(top, bottom)
    }

    /**
     * Common immutable inset values and factories.
     */
    public companion object {
        /**
         * Insets with no distance on any side.
         */
        public val Zero: Insets = Insets()

        /**
         * Creates equal distances on every side.
         *
         * @param value the non-negative distance for every side.
         * @return an inset value with the requested distance.
         * @throws IllegalArgumentException when [value] is negative.
         * @throws ArithmeticException when twice [value] cannot be represented as an [Int].
         */
        public fun all(value: Int): Insets = Insets(left = value, top = value, right = value, bottom = value)

        /**
         * Creates equal horizontal and equal vertical distances.
         *
         * @param horizontal the non-negative distance on the left and right sides.
         * @param vertical the non-negative distance on the top and bottom sides.
         * @return an inset value with the requested axis distances.
         * @throws IllegalArgumentException when either distance is negative.
         * @throws ArithmeticException when twice either distance cannot be represented as an [Int].
         */
        public fun symmetric(
            horizontal: Int,
            vertical: Int,
        ): Insets = Insets(left = horizontal, top = vertical, right = horizontal, bottom = vertical)
    }
}
