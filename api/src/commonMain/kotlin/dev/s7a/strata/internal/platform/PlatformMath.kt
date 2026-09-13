package dev.s7a.strata.internal.platform

/**
 * Checked integer arithmetic shared by JVM and JavaScript layout.
 * Overflow throws ArithmeticException before a caller can use a wrapped geometry value.
 */
internal object PlatformMath {
    /**
     * Adds two integers exactly.
     */
    fun addExact(
        a: Int,
        b: Int,
    ): Int = toIntExact(a.toLong() + b.toLong())

    /**
     * Subtracts two integers exactly.
     */
    fun subtractExact(
        a: Int,
        b: Int,
    ): Int = toIntExact(a.toLong() - b.toLong())

    /**
     * Multiplies two integers exactly.
     */
    fun multiplyExact(
        a: Int,
        b: Int,
    ): Int = toIntExact(a.toLong() * b.toLong())

    /**
     * Adds two long integers exactly.
     */
    fun addExact(
        a: Long,
        b: Long,
    ): Long {
        val result = a + b
        if (((a xor result) and (b xor result)) < 0L) throw ArithmeticException("Long overflow")
        return result
    }

    /**
     * Subtracts two long integers exactly.
     */
    fun subtractExact(
        a: Long,
        b: Long,
    ): Long {
        val result = a - b
        if (((a xor b) and (a xor result)) < 0L) throw ArithmeticException("Long overflow")
        return result
    }

    /**
     * Multiplies two long integers exactly.
     */
    fun multiplyExact(
        a: Long,
        b: Long,
    ): Long {
        val negatesFirstMinimum = a == Long.MIN_VALUE && b == -1L
        val negatesSecondMinimum = b == Long.MIN_VALUE && a == -1L
        if (negatesFirstMinimum || negatesSecondMinimum) throw ArithmeticException("Long overflow")
        val result = a * b
        if (b != 0L && result / b != a) throw ArithmeticException("Long overflow")
        return result
    }

    /**
     * Increments a long integer exactly.
     */
    fun incrementExact(value: Long): Long = addExact(value, 1L)

    /**
     * Narrows a long integer or rejects overflow.
     */
    fun toIntExact(value: Long): Int {
        if (value < Int.MIN_VALUE.toLong() || Int.MAX_VALUE.toLong() < value) throw ArithmeticException("Integer overflow")
        return value.toInt()
    }

    /**
     * Divides toward negative infinity, preserving JVM minimum-value overflow behavior.
     */
    fun floorDiv(
        a: Long,
        b: Long,
    ): Long {
        val quotient = a / b
        return if ((a xor b) < 0L && a % b != 0L) quotient - 1L else quotient
    }

    /**
     * Computes a remainder having the divisor's sign.
     */
    fun floorMod(
        a: Long,
        b: Long,
    ): Long = a - floorDiv(a, b) * b

    /**
     * Computes an integer remainder having the divisor's sign.
     */
    fun floorMod(
        a: Int,
        b: Int,
    ): Int = floorMod(a.toLong(), b.toLong()).toInt()
}
