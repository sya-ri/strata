package dev.s7a.strata.geometry

/**
 * An immutable non-negative integer extent.
 *
 * @property width the horizontal extent.
 * @property height the vertical extent.
 */
public data class IntSize(
    public val width: Int,
    public val height: Int,
) {
    init {
        require(0 <= width) { "Width must be non-negative." }
        require(0 <= height) { "Height must be non-negative." }
    }

    /**
     * Common size constants.
     */
    public companion object {
        /**
         * The empty extent.
         */
        public val Zero: IntSize = IntSize(0, 0)
    }
}
