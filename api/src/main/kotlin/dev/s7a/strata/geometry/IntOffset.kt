package dev.s7a.strata.geometry

import dev.s7a.strata.internal.toIntExact

/**
 * An immutable integer coordinate or displacement.
 *
 * @property x the horizontal coordinate.
 * @property y the vertical coordinate.
 */
public data class IntOffset(
    public val x: Int,
    public val y: Int,
) {
    /**
     * Adds two offsets and fails when either coordinate would overflow.
     *
     * @param other the offset to add.
     * @return the checked coordinate sum.
     */
    public operator fun plus(other: IntOffset): IntOffset = IntOffset((x.toLong() + other.x).toIntExact(), (y.toLong() + other.y).toIntExact())

    /**
     * Subtracts two offsets and fails when either coordinate would overflow.
     *
     * @param other the offset to subtract.
     * @return the checked coordinate difference.
     */
    public operator fun minus(other: IntOffset): IntOffset = IntOffset((x.toLong() - other.x).toIntExact(), (y.toLong() - other.y).toIntExact())

    /**
     * Common offset constants.
     */
    public companion object {
        /**
         * The origin coordinate.
         */
        public val Zero: IntOffset = IntOffset(0, 0)
    }
}
