package dev.s7a.strata.internal.platform

/**
 * Exact integer operations used by finite binary-float layout weights.
 */
internal expect class PlatformBigInteger {
    /**
     * Returns this value shifted left by a non-negative distance.
     */
    fun shiftLeft(distance: Int): PlatformBigInteger

    /**
     * Returns the exact sum.
     */
    fun add(other: PlatformBigInteger): PlatformBigInteger

    /**
     * Returns the exact product.
     */
    fun multiply(other: PlatformBigInteger): PlatformBigInteger

    /**
     * Returns the integral quotient, rejecting a zero divisor.
     */
    fun divide(other: PlatformBigInteger): PlatformBigInteger

    /**
     * Returns negative one, zero, or positive one according to the value.
     */
    fun signum(): Int

    /**
     * Narrows to a long integer or throws ArithmeticException on overflow.
     */
    fun longValueExact(): Long

    /**
     * Creates exact values used to normalize binary weights.
     */
    companion object {
        /**
         * The immutable additive identity.
         */
        val ZERO: PlatformBigInteger

        /**
         * Converts a long integer without rounding.
         */
        fun valueOf(value: Long): PlatformBigInteger
    }
}
