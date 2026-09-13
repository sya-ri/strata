@file:Suppress("UnusedParameter") // JavaScript intrinsics consume the named Kotlin parameters.

package dev.s7a.strata.internal.platform

/**
 * Uses JavaScript BigInt to preserve exact layout weights without double rounding.
 */
internal actual class PlatformBigInteger(
    private val value: dynamic,
) {
    /**
     * Returns this value shifted left by a non-negative distance.
     */
    actual fun shiftLeft(distance: Int): PlatformBigInteger = PlatformBigInteger(shift(value, distance))

    /**
     * Returns the exact sum.
     */
    actual fun add(other: PlatformBigInteger): PlatformBigInteger = PlatformBigInteger(value + other.value)

    /**
     * Returns the exact product.
     */
    actual fun multiply(other: PlatformBigInteger): PlatformBigInteger = PlatformBigInteger(value * other.value)

    /**
     * Returns the integral quotient, rejecting a zero divisor.
     */
    actual fun divide(other: PlatformBigInteger): PlatformBigInteger = PlatformBigInteger(value / other.value)

    /**
     * Returns negative one, zero, or positive one according to the value.
     */
    actual fun signum(): Int = sign(value)

    /**
     * Narrows to a long integer or throws ArithmeticException on overflow.
     */
    actual fun longValueExact(): Long =
        value.toString().unsafeCast<String>().toLongOrNull()
            ?: throw ArithmeticException("Long overflow")

    /**
     * Creates exact values used to normalize binary weights.
     */
    actual companion object {
        /**
         * The immutable additive identity.
         */
        actual val ZERO: PlatformBigInteger = valueOf(0L)

        /**
         * Converts a long integer without rounding.
         */
        actual fun valueOf(value: Long): PlatformBigInteger = PlatformBigInteger(parse(value.toString()))

        private fun parse(decimal: String): dynamic = js("BigInt(decimal)")

        private fun shift(
            value: dynamic,
            distance: Int,
        ): dynamic = js("value << BigInt(distance)")

        private fun sign(value: dynamic): Int = js("value < 0 ? -1 : (value == 0 ? 0 : 1)")
    }
}
