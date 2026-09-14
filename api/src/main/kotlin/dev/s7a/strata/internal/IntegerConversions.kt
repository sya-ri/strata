package dev.s7a.strata.internal

/**
 * Narrows an integer at a coordinate or extent boundary, rejecting values outside the Int range.
 */
internal fun Long.toIntExact(): Int {
    if (this < Int.MIN_VALUE.toLong() || Int.MAX_VALUE.toLong() < this) throw ArithmeticException("Integer overflow")
    return toInt()
}
