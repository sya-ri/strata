package dev.s7a.strata.geometry

/**
 * An immutable finite two-dimensional coordinate or displacement.
 *
 * The value owns no external resources and is safe to share between threads.
 * Arithmetic creates another validated value and fails when a result is not finite.
 *
 * @property x horizontal coordinate or displacement.
 * @property y vertical coordinate or displacement.
 * @throws IllegalArgumentException when either component is not finite.
 */
public data class DoubleOffset(
    public val x: Double,
    public val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Offset components must be finite." }
    }

    /**
     * Adds a finite displacement.
     *
     * @param other displacement to add.
     * @return the translated value.
     * @throws IllegalArgumentException when a resulting component is not finite.
     */
    public operator fun plus(other: DoubleOffset): DoubleOffset = DoubleOffset(x + other.x, y + other.y)

    /**
     * Subtracts a finite displacement.
     *
     * @param other displacement to subtract.
     * @return the translated value.
     * @throws IllegalArgumentException when a resulting component is not finite.
     */
    public operator fun minus(other: DoubleOffset): DoubleOffset = DoubleOffset(x - other.x, y - other.y)

    /**
     * Common coordinate constants.
     */
    public companion object {
        /**
         * The origin and zero displacement.
         */
        public val Zero: DoubleOffset = DoubleOffset(0.0, 0.0)
    }
}
