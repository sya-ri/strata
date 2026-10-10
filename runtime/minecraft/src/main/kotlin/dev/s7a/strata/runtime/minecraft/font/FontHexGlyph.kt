package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable sixteen-row hexadecimal glyph data with sparse bit access.
 *
 * @property width source row width in pixels.
 * @param rows sixteen copied row bitfields, with the leftmost pixel in the highest occupied source bit.
 */
internal class FontHexGlyph(
    val width: Int,
    rows: LongArray,
) {
    private val rows: LongArray = rows.copyOf()

    /**
     * Returns a source-row bit, padding explicit overrides outside the native 32-bit row with transparency.
     */
    fun ink(
        x: Int,
        y: Int,
    ): Boolean = x in 0..31 && ((rows[y] shl (32 - width)) ushr (31 - x)) and 1L != 0L

    /**
     * Finds inclusive natural ink bounds; native empty rows retain one extra transparent column.
     * Packed admitted widths use the union of source columns across every owned row.
     * Other internal widths retain the original scalar shift semantics.
     */
    fun bounds(): IntRange {
        if (width in 8..32 && width % 8 == 0) {
            var columns = 0L
            for (row in rows) columns = columns or row
            columns = columns and ((1L shl width) - 1L)
            if (columns == 0L) return 0..width
            return (columns.countLeadingZeroBits() - (Long.SIZE_BITS - width))..(width - 1 - columns.countTrailingZeroBits())
        }

        // Other internal widths retain the original JVM shift and source-column semantics.
        var left = width
        var right = -1
        for (y in rows.indices) {
            for (x in 0 until width) {
                if (ink(x, y)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
        }
        return if (right < left) 0..width else left..right
    }
}
