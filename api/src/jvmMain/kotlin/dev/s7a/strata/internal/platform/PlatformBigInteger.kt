package dev.s7a.strata.internal.platform

import java.math.BigInteger

/**
 * Delegates exact layout weight arithmetic to the JVM implementation.
 */
internal actual class PlatformBigInteger(
    private val value: BigInteger,
) {
    /**
     * Returns this value shifted left by a non-negative distance.
     */
    actual fun shiftLeft(distance: Int): PlatformBigInteger = PlatformBigInteger(value.shiftLeft(distance))

    /**
     * Returns the exact sum.
     */
    actual fun add(other: PlatformBigInteger): PlatformBigInteger = PlatformBigInteger(value.add(other.value))

    /**
     * Returns the exact product.
     */
    actual fun multiply(other: PlatformBigInteger): PlatformBigInteger = PlatformBigInteger(value.multiply(other.value))

    /**
     * Returns the integral quotient, rejecting a zero divisor.
     */
    actual fun divide(other: PlatformBigInteger): PlatformBigInteger = PlatformBigInteger(value.divide(other.value))

    /**
     * Returns negative one, zero, or positive one according to the value.
     */
    actual fun signum(): Int = value.signum()

    /**
     * Narrows to a long integer or throws ArithmeticException on overflow.
     */
    actual fun longValueExact(): Long = value.longValueExact()

    /**
     * Creates exact values used to normalize binary weights.
     */
    actual companion object {
        /**
         * The immutable additive identity.
         */
        actual val ZERO: PlatformBigInteger = PlatformBigInteger(BigInteger.ZERO)

        /**
         * Converts a long integer without rounding.
         */
        actual fun valueOf(value: Long): PlatformBigInteger = PlatformBigInteger(BigInteger.valueOf(value))
    }
}
