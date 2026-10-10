package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent

/**
 * Scalar CPU reference independent of Strata's text renderer, range derivation, command collector and rasterizer.
 * Agreement deliberately uses the original complete join/prefix algorithm; expected texels are written directly.
 * Only the current operation is retained, and this model is never instantiated during timing.
 */
internal class TextFieldPreeditReference(
    private val resourceFonts: Boolean,
    private val negativeAdvance: Boolean,
) {
    var value: String = "A"
    var cursor: Int = 1
    var composition: TextInputEvent.Preedit? = null
    var focused: Boolean = true
    var enabled: Boolean = true
    var wideFont: Boolean = false
    var width: Int = 80
    var background: Int = 0xFF426789.toInt()

    /**
     * Applies the original complete full/block validation, independent of production helpers.
     */
    fun input(event: TextInputEvent.Preedit): InputResult {
        val texts = listOf(event.fullText) + event.blocks
        val accepted = texts.all { text ->
            text.codePoints().allMatch { scalar ->
                0x20 <= scalar && (scalar in 0xD800..0xDFFF).not() && (scalar in setOf(0x7F, 0x85, 0xA7, 0x2028, 0x2029)).not()
            }
        }
        val split = event.caretPosition in 1 until event.fullText.length &&
            event.fullText[event.caretPosition - 1].isHighSurrogate() && event.fullText[event.caretPosition].isLowSurrogate()
        if (enabled.not() || focused.not() || accepted.not() || split) return InputResult.Ignored
        composition = event.takeIf { it.fullText.isNotEmpty() }
        return InputResult.Consumed
    }

    /**
     * Returns ordered underline then insertion caret geometry using original signed width and clipping.
     */
    fun rectangles(): List<IntRect> {
        if (focused.not() || enabled.not()) return emptyList()
        val event = composition
        val text = value.substring(0, cursor) + (event?.fullText ?: "") + value.substring(cursor)
        val caret = cursor + (event?.caretPosition ?: 0)
        val start = visibleStart(text, caret)
        var end = start
        while (end < text.length) {
            val next = text.offsetByCodePoints(end, 1)
            if (width - 8 < width(text, start, next)) break
            end = next
        }
        return buildList {
            if (event != null && 0 <= event.focusedBlock && event.blocks.joinToString("") == event.fullText) {
                val blockStart = cursor + event.blocks.take(event.focusedBlock).sumOf(String::length)
                val blockEnd = blockStart + event.blocks[event.focusedBlock].length
                val first = maxOf(start, blockStart)
                val last = minOf(end, blockEnd)
                if (first < last) {
                    val x1 = 4 + width(text, start, first)
                    val x2 = 4 + width(text, start, last)
                    val left = minOf(x1, x2).coerceIn(0, width)
                    val right = maxOf(x1, x2).coerceIn(0, width)
                    if (left < right) add(IntRect(left, 15, right, 16))
                }
            }
            val x = 4 + width(text, start, caret)
            if (x in 0 until width) add(IntRect(x, 5, x + 1, 16))
        }
    }

    /**
     * Computes the entire expected field image directly, including independent glyph texels and decorations.
     */
    fun pixels(viewport: IntSize): IntArray {
        val pixels = IntArray(viewport.width * viewport.height)
        for (y in 0 until 20) for (x in 0 until width) pixels[y * viewport.width + x] = background
        val event = composition
        val text = value.substring(0, cursor) + (event?.fullText ?: "") + value.substring(cursor)
        val caret = cursor + (event?.caretPosition ?: 0)
        val start = visibleStart(text, caret)
        var end = start
        while (end < text.length) {
            val next = text.offsetByCodePoints(end, 1)
            if (width - 8 < width(text, start, next)) break
            end = next
        }
        if (resourceFonts.not()) {
            var offset = start
            while (offset < end) {
                val x = 4 + width(text, start, offset)
                if (text[offset] != ' ' && x in 0 until width) pixels[6 * viewport.width + x] = 0xFF404040.toInt()
                offset = text.offsetByCodePoints(offset, 1)
            }
        }
        val rectangles = rectangles()
        rectangles.forEachIndexed { index, bounds ->
            val color = if (rectangles.size == 2 && index == 0) underline else caretColor
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) pixels[y * viewport.width + x] = color
        }
        // A sole underline can occur when a signed caret lies outside the field.
        if (rectangles.size == 1 && rectangles.single().height == 1) {
            val bounds = rectangles.single()
            for (x in bounds.left until bounds.right) pixels[15 * viewport.width + x] = underline
        }
        return pixels
    }

    /**
     * Independent ordered image submissions: nine source-grid cells followed by logical-order foreground glyphs.
     * Each triple is source image size, source rectangle and original destination; production painting is never called.
     */
    fun images(): List<Triple<IntSize, IntRect, IntRect>> = buildList {
        val xs = listOf(0, 1, width - 1, width)
        val ys = listOf(0, 1, 19, 20)
        for (y in 0..2) for (x in 0..2) {
            add(Triple(IntSize(3, 3), IntRect(x, y, x + 1, y + 1), IntRect(xs[x], ys[y], xs[x + 1], ys[y + 1])))
        }
        if (resourceFonts.not()) {
            val event = composition
            val text = value.substring(0, cursor) + (event?.fullText ?: "") + value.substring(cursor)
            val caret = cursor + (event?.caretPosition ?: 0)
            val start = visibleStart(text, caret)
            var offset = start
            while (offset < text.length) {
                val next = text.offsetByCodePoints(offset, 1)
                if (width - 8 < width(text, start, next)) break
                val x = 4 + width(text, start, offset)
                if (text[offset] != ' ') add(Triple(IntSize(8, 8), IntRect(0, 0, 8, 8), IntRect(x, 6, x + 8, 14)))
                offset = next
            }
        }
    }

    private fun visibleStart(text: String, caret: Int): Int {
        var offset = 0
        var remaining = width(text, 0, caret)
        while (width - 8 < remaining) {
            val next = text.offsetByCodePoints(offset, 1)
            remaining -= width(text, offset, next)
            offset = next
        }
        return offset
    }

    private fun width(text: String, start: Int, end: Int): Int {
        var result = 0
        var offset = start
        while (offset < end) {
            val scalar = text.codePointAt(offset)
            result += when {
                negativeAdvance && scalar == 0x1F642 -> -10
                wideFont -> 3
                scalar == 0x20 -> 4
                else -> 2
            }
            offset += Character.charCount(scalar)
        }
        return result
    }

    /**
     * Exact detached fixture colors, unrelated to runtime constants.
     */
    companion object {
        val underline: Int = 0xFF106030.toInt()
        val caretColor: Int = 0xFF203020.toInt()
    }
}
