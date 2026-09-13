package dev.s7a.strata.internal.platform

/**
 * Returns the scalar or isolated UTF-16 surrogate at [index], rejecting an invalid index.
 */
internal fun String.scalarAt(index: Int): Int {
    val first = this[index]
    if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return 0x10000 + ((first.code - 0xD800) shl 10) + this[index + 1].code - 0xDC00
    }
    return first.code
}

/**

 * Appends a validated Unicode scalar as one or two UTF-16 code units.

 */
internal fun StringBuilder.appendScalar(scalar: Int): StringBuilder {
    require(scalar in 0..0x10FFFF && (scalar in 0xD800..0xDFFF).not()) { "Invalid Unicode scalar." }
    if (scalar < 0x10000) {
        append(scalar.toChar())
    } else {
        val rest = scalar - 0x10000
        append((0xD800 + (rest shr 10)).toChar())
        append((0xDC00 + (rest and 0x3FF)).toChar())
    }
    return this
}
