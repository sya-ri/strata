package dev.s7a.strata

import dev.s7a.strata.internal.platform.appendScalar
import dev.s7a.strata.internal.platform.scalarAt

/**
 * Independent baseline scalar loop and explicit code-unit oracle, neither of which calls the production normalizer.
 * Both preserve the original scalar-before-length failure ordering and exact exception messages.
 */
internal object TextAreaNormalizationReference {
    /**
     * Replays the complete original eager-output scalar loop, including CRLF contraction.
     */
    fun scalar(value: String, maximum: Int): String {
        require(0 < maximum) { "Text area maximum length must be positive." }
        val result = StringBuilder(minOf(value.length, maximum))
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.scalarAt(offset)
            require((codePoint in 0xD800..0xDFFF).not()) { "Text area value contains an isolated surrogate." }
            when (codePoint) {
                0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029 -> {
                    result.append('\n')
                    if (codePoint == 0x0D && offset + 1 < value.length && value[offset + 1] == '\n') offset += 1
                }

                else -> {
                    require(0x20 <= codePoint && codePoint != 0x7F && codePoint != 0xA7) {
                        "Text area value contains a control character or formatting marker."
                    }
                    result.appendScalar(codePoint)
                }
            }
            require(result.length <= maximum) { "Text area value exceeds its maximum length after newline normalization." }
            offset += (if (codePoint < 0x10000) 1 else 2)
        }
        return result.toString()
    }

    /**
     * Decodes UTF-16 by inspecting individual code units, with no platform scalar or append helper.
     */
    fun utf16(value: String, maximum: Int): String {
        require(0 < maximum) { "Text area maximum length must be positive." }
        val output = mutableListOf<Char>()
        var index = 0
        while (index < value.length) {
            val unit = value[index]
            when (unit) {
                in '\uD800'..'\uDBFF' -> {
                    require(index + 1 < value.length && value[index + 1] in '\uDC00'..'\uDFFF') {
                        "Text area value contains an isolated surrogate."
                    }
                    output.add(unit)
                    index += 1
                    output.add(value[index])
                }

                in '\uDC00'..'\uDFFF' -> throw IllegalArgumentException("Text area value contains an isolated surrogate.")
                '\r' -> {
                    output.add('\n')
                    if (index + 1 < value.length && value[index + 1] == '\n') index += 1
                }

                '\n', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029' -> output.add('\n')
                else -> {
                    require('\u0020' <= unit && unit != '\u007F' && unit != '\u00A7') {
                        "Text area value contains a control character or formatting marker."
                    }
                    output.add(unit)
                }
            }
            require(output.size <= maximum) { "Text area value exceeds its maximum length after newline normalization." }
            index += 1
        }
        return output.toCharArray().concatToString()
    }
}
