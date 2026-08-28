package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Stateless scalar-safe hard-break and wrapping engine for both display and editable text.
 *
 * Each call borrows its owner-thread renderer, measures the original font-selected scalars once, and produces only a current-value layout.
 * Word wrapping prefers whitespace boundaries, preserves that whitespace, and falls back to scalar boundaries for words or scripts without spaces.
 * Explicit breaks recognize CRLF as one break and LF, CR, VT, FF, NEL, LS, and PS individually.
 */
internal object MinecraftTextLineBreaker {
    /**
     * Lays out one immutable value against structural width and optional display-height constraints.
     *
     * @param content validated logical multiline text with original font provenance.
     * @param renderer borrowed owner-thread text and metric service; not retained by the result.
     * @param policy wrapping, maximum line count, overflow, and spacing policy.
     * @param maxWidth non-negative available logical width; [Int.MAX_VALUE] disables soft wrapping.
     * @param style profile color and shadow policy.
     * @param enabled enabled editing tint when [style] is TextField.
     * @param logicalOrder preserves logical scalar drawing for editable text.
     * @param maxHeight non-negative display viewport height; editable content uses the unbounded default.
     * @return detached layout, including original source semantics even after truncation.
     * @throws IllegalArgumentException when dimensions or compatibility text are unsupported.
     * @throws ArithmeticException when the complete current layout cannot be represented by integer geometry.
     * @throws IllegalStateException when the renderer is closed or accessed off its owner thread.
     */
    @JvmSynthetic
    internal fun create(
        content: MinecraftTextContent,
        renderer: MinecraftTextRenderer,
        policy: TextLayout.Multiline,
        maxWidth: Int,
        style: TextStyle,
        enabled: Boolean = true,
        logicalOrder: Boolean = false,
        maxHeight: Int = Int.MAX_VALUE,
    ): MinecraftTextLayout {
        require(0 <= maxWidth && 0 <= maxHeight) { "Text layout dimensions must be non-negative." }
        val step = Math.addExact(9, policy.lineSpacing)
        val advances = measure(content, renderer)
        val ranges = breakLines(content.value, advances, renderer, maxWidth, policy.wrap)
        val heightLines =
            when (maxHeight) {
                Int.MAX_VALUE -> Int.MAX_VALUE
                0 -> 0
                else -> 1 + (maxHeight - 1) / step
            }
        val count = minOf(ranges.size, policy.maxLines, heightLines)
        var truncated = count < ranges.size
        val lines = ArrayList<MinecraftTextLine>(count)
        for (index in 0 until count) {
            val range = ranges[index]
            val widths = positions(content.value, range, advances)
            val overflow = maxWidth < renderer.roundedWidth(widths.last()) || (index == count - 1 && count < ranges.size)
            val line = createLine(content, range, widths, renderer, style, enabled, logicalOrder, maxWidth, policy.overflow == TextOverflow.Ellipsis && overflow)
            truncated = truncated || overflow
            lines.add(line)
        }
        val height = if (lines.isEmpty()) 0 else Math.addExact(Math.multiplyExact(lines.size - 1, step), 9)
        val width = minOf(maxWidth, lines.maxOfOrNull { it.run.size.width } ?: 0)
        return MinecraftTextLayout(content, lines, IntSize(width, height), step, truncated)
    }

    private fun measure(
        content: MinecraftTextContent,
        renderer: MinecraftTextRenderer,
    ): FloatArray {
        val advances = FloatArray(content.value.length)
        var offset = 0
        while (offset < content.value.length) {
            val codePoint = content.value.codePointAt(offset)
            if (MinecraftTextContent.isHardBreak(codePoint).not()) advances[offset] = renderer.advance(content.fontAt(offset), codePoint)
            offset += Character.charCount(codePoint)
        }
        return advances
    }

    private fun breakLines(
        value: String,
        advances: FloatArray,
        renderer: MinecraftTextRenderer,
        maxWidth: Int,
        wrap: TextWrap,
    ): List<Range> {
        val lines = ArrayList<Range>()
        var start = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (MinecraftTextContent.isHardBreak(codePoint)) {
                val next = if (codePoint == 0x0D && offset + 1 < value.length && value[offset + 1] == '\n') offset + 2 else offset + 1
                wrapParagraph(value, start, offset, next, advances, renderer, maxWidth, wrap, lines)
                start = next
                offset = next
            } else {
                offset += Character.charCount(codePoint)
            }
        }
        wrapParagraph(value, start, value.length, value.length, advances, renderer, maxWidth, wrap, lines)
        return lines
    }

    private fun wrapParagraph(
        value: String,
        start: Int,
        end: Int,
        nextStart: Int,
        advances: FloatArray,
        renderer: MinecraftTextRenderer,
        maxWidth: Int,
        wrap: TextWrap,
        output: MutableList<Range>,
    ) {
        if (start == end || wrap == TextWrap.None || maxWidth == Int.MAX_VALUE) {
            output.add(Range(start, end, nextStart))
            return
        }
        var first = start
        while (first < end) {
            val last = wrapEnd(value, first, end, advances, renderer, maxWidth, wrap)
            output.add(Range(first, last, if (last == end) nextStart else last))
            first = last
        }
    }

    private fun wrapEnd(
        value: String,
        start: Int,
        end: Int,
        advances: FloatArray,
        renderer: MinecraftTextRenderer,
        maxWidth: Int,
        wrap: TextWrap,
    ): Int {
        var offset = start
        var width = 0f
        var wordEnd = start
        while (offset < end) {
            val codePoint = value.codePointAt(offset)
            val next = offset + Character.charCount(codePoint)
            val candidate = width + advances[offset]
            val opportunity = MinecraftTextBreakOpportunity.of(codePoint)
            val overflow = maxWidth < renderer.roundedWidth(candidate)
            if (overflow && start < offset) {
                return if (wrap == TextWrap.Word && start < wordEnd) wordEnd else offset
            }
            width = candidate
            offset = next
            if (opportunity == MinecraftTextBreakOpportunity.Whitespace) wordEnd = offset
            if (overflow && wrap == TextWrap.Character) return offset
        }
        return end
    }

    private fun positions(
        value: String,
        range: Range,
        advances: FloatArray,
    ): FloatArray {
        val result = FloatArray(value.codePointCount(range.start, range.end) + 1)
        var index = 1
        var offset = range.start
        while (offset < range.end) {
            result[index] = result[index - 1] + advances[offset]
            index++
            offset += Character.charCount(value.codePointAt(offset))
        }
        return result
    }

    private fun createLine(
        content: MinecraftTextContent,
        range: Range,
        widths: FloatArray,
        renderer: MinecraftTextRenderer,
        style: TextStyle,
        enabled: Boolean,
        logicalOrder: Boolean,
        maxWidth: Int,
        ellipsis: Boolean,
    ): MinecraftTextLine {
        val offsets = IntArray(widths.size)
        offsets[0] = range.start
        for (index in 1 until offsets.size) offsets[index] = content.value.offsetByCodePoints(offsets[index - 1], 1)
        val markerEnd = if (ellipsis) ellipsisEnd(content, offsets, widths, renderer, maxWidth) else null
        val last = markerEnd ?: offsets.lastIndex
        val end = offsets[last]
        val literal = content.slice(range.start, end)
        val text =
            if (markerEnd != null) {
                val appended = MinecraftTextContent.create(UiText.concat(literal, UiText.Literal("...").withFont(markerFont(content, range.start, end))))
                appended.slice(0, appended.value.length)
            } else {
                literal
            }
        val run = renderer.create(text, style, enabled, logicalOrder = logicalOrder)
        val positions = IntArray(last + 1) { renderer.roundedWidth(widths[it]) }
        return MinecraftTextLine(range.start, end, range.nextStart, run, offsets.copyOf(last + 1), positions)
    }

    private fun ellipsisEnd(
        content: MinecraftTextContent,
        offsets: IntArray,
        widths: FloatArray,
        renderer: MinecraftTextRenderer,
        maxWidth: Int,
    ): Int? {
        for (index in offsets.lastIndex downTo 0) {
            val font = markerFont(content, offsets[0], offsets[index])
            val advance = renderer.advance(font, '.'.code)
            val width = ((widths[index] + advance) + advance) + advance
            if (renderer.roundedWidth(width) <= maxWidth) return index
        }
        return null
    }

    private fun markerFont(
        content: MinecraftTextContent,
        start: Int,
        end: Int,
    ) = when {
        start < end -> content.fontAt(content.value.offsetByCodePoints(end, -1))
        start < content.value.length -> content.fontAt(start)
        content.value.isNotEmpty() -> content.fontAt(content.value.offsetByCodePoints(content.value.length, -1))
        else -> content.fontAt(0)
    }

    private data class Range(
        val start: Int,
        val end: Int,
        val nextStart: Int,
    )
}
