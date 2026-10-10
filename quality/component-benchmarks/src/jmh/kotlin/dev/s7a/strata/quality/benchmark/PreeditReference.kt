package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.input.TextInputEvent

/**
 * Independent scalar oracle with every raw UTF-16 boundary, including the interior of CRLF.
 * Surrogate interiors remain unmapped; blocks are decoded independently even after a mismatch.
 * This oracle never invokes a Strata normalizer or uses its newline/scalar helpers.
 */
internal object PreeditReference {
    /**
     * Detached accepted output and its raw boundary map; no event or caller block list is retained.
     */
    internal data class Result(
        val text: String,
        val caret: Int,
        val focused: IntRange?,
        val boundaries: List<Int?>,
    )

    /**
     * Returns independently normalized text/ranges, or null after the declared rejection boundary.
     */
    internal fun normalize(
        event: TextInputEvent.Preedit,
        remaining: Int,
    ): Result? {
        require(0 <= remaining)
        val budget = 2L * remaining
        if (budget < event.fullText.length || budget + 1L < event.blocks.size) return null
        var blockUnits = 0L
        var agreement = true
        var focus: IntRange? = null
        event.blocks.forEachIndexed { index, value ->
            val start = blockUnits
            blockUnits += value.length
            if (budget < blockUnits || Int.MAX_VALUE < blockUnits || decode(value) == null) return null
            if (start + value.length <= event.fullText.length) {
                agreement = agreement && event.fullText.substring(start.toInt(), blockUnits.toInt()) == value
            } else {
                agreement = false
            }
            if (index == event.focusedBlock) focus = start.toInt() until blockUnits.toInt()
        }
        val decoded = decode(event.fullText) ?: return null
        if (remaining < decoded.first.length) return null
        val caret = decoded.second[event.caretPosition] ?: return null
        val rawFocus = focus.takeIf { agreement && blockUnits == event.fullText.length.toLong() }
        val range =
            rawFocus?.let {
                val start = decoded.second[it.first] ?: return null
                val end = decoded.second[it.last + 1] ?: return null
                start until end
            }
        return Result(decoded.first, caret, range, decoded.second)
    }

    private fun decode(raw: String): Pair<String, List<Int?>>? {
        val output = ArrayList<Char>()
        val boundaries = MutableList<Int?>(raw.length + 1) { null }
        var cursor = 0
        boundaries[0] = 0
        while (cursor < raw.length) {
            val first = raw[cursor]
            val units: Int
            val scalar: Int
            if (first.isHighSurrogate()) {
                if (raw.length <= cursor + 1 || raw[cursor + 1].isLowSurrogate().not()) return null
                units = 2
                scalar = 0x10000 + ((first.code - 0xD800) shl 10) + raw[cursor + 1].code - 0xDC00
            } else {
                if (first.isLowSurrogate()) return null
                units = 1
                scalar = first.code
            }
            val hardBreak = scalar in setOf(10, 13, 11, 12, 0x85, 0x2028, 0x2029)
            if (hardBreak.not() && (scalar < 32 || scalar == 127 || scalar == 0xA7)) return null
            if (hardBreak) {
                output.add('\n')
                cursor++
                boundaries[cursor] = output.size
                if (scalar == 13 && cursor < raw.length && raw[cursor] == '\n') cursor++
            } else {
                repeat(units) { output.add(raw[cursor++]) }
            }
            boundaries[cursor] = output.size
        }
        return output.toCharArray().concatToString() to boundaries
    }
}
