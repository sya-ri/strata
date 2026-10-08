package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Shape
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.text.UiText
import kotlin.math.abs

/**
 * Independent scalar/Float-prefix and white-ink reference for the prepared logical inputs.
 * Expected boundaries never come from a target line; temporary oracle rows are released before timing.
 * Pixel checks cover a bounded 64 by 64 window at densities one through three, including viewport clipping.
 */
internal class TextLineLayoutReference(
    private val value: String,
    private val fontAt: (Int) -> ResourceId,
    private val assets: TextLineLayoutAssets,
    private val consumer: Consumer,
    private val shape: Shape,
    private val size: IntSize,
) {
    private val width = if (consumer === Consumer.TextArea) size.width - 8 else size.width
    private val rows = rows()

    /**
     * Requires the exact admitted scalar arrays, forward native rounding and detached visible run text.
     */
    internal fun verify(layout: Any) {
        val content = TextAreaInputAccess.field(layout, "content")
        check(TextAreaInputAccess.field(content, "value") == value)
        val lines = TextAreaInputAccess.lines(layout)
        check(lines.size == rows.size)
        for ((line, row) in lines.zip(rows)) verifyLine(line, row)
        check(TextAreaInputAccess.field(layout, "lineStep") == 11)
    }

    /**
     * Checks synthetic glyph pixels against independently positioned foreground/shadow commands.
     * The editor frame and caret are checked by their separate restoration oracle.
     */
    internal fun verifyPixels(frame: RuntimeUiFrame) {
        val bounds = if (consumer === Consumer.TextArea) IntRect(4, 4, size.width - 4, size.height - 4) else IntRect(0, 0, size.width, size.height)
        val actual =
            buildList {
                add(DrawCommand.PushClip(bounds))
                addAll(frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>())
                add(DrawCommand.PopClip)
            }
        val expected =
            buildList {
                add(DrawCommand.PushClip(bounds))
                if (shape !== Shape.ExceptionalMetrics) rows.forEachIndexed { index, row -> addAll(glyphs(row, index)) }
                add(DrawCommand.PopClip)
            }
        val window = IntSize(minOf(64, size.width), minOf(64, size.height))
        for (scale in 1..3) {
            check(rasterizeHeadless(actual, window, scale).copyArgb().contentEquals(rasterizeHeadless(expected, window, scale).copyArgb()))
        }
    }

    /**
     * Requires independently selected extrema for the focused block on the first logical line.
     */
    internal fun verifyUnderline(
        underline: Any?,
        range: IntRange?,
    ) {
        if (range == null) {
            check(underline == null)
            return
        }
        val row = rows.first()
        val first = row.offsets.indexOf(range.first)
        val last = row.offsets.indexOf(range.last + 1)
        check(0 <= first && first <= last)
        val coordinates = row.positions.slice(first..last)
        check(TextAreaInputAccess.field(checkNotNull(underline), "firstLine") == 0)
        check(TextAreaInputAccess.field(checkNotNull(underline), "ranges") == listOf(coordinates.min()..coordinates.max()))
    }

    private fun verifyLine(
        line: Any,
        row: Row,
    ) {
        check(TextAreaInputAccess.field(line, "start") == row.offsets.first())
        check(TextAreaInputAccess.field(line, "end") == row.offsets.last())
        check(TextAreaInputAccess.field(line, "nextStart") == row.nextStart)
        check((TextAreaInputAccess.field(line, "offsets") as IntArray).contentEquals(row.offsets))
        check((TextAreaInputAccess.field(line, "positions") as IntArray).contentEquals(row.positions))
        val run = TextAreaInputAccess.field(line, "run")
        check(literal(TextAreaInputAccess.field(run, "text") as UiText) == value.substring(row.offsets.first(), row.offsets.last()) + if (row.ellipsis) "..." else "")
        val lookup = TextAreaLookupAccess.create(line)
        for (x in listOf(Int.MIN_VALUE, -1, 0, row.positions.first(), row.positions[row.positions.size / 2], row.positions.last(), Int.MAX_VALUE)) {
            val index = row.positions.indices.minBy { abs(row.positions[it].toLong() - x.toLong()) }
            check(lookup.applyAsInt(x) == row.offsets[index])
        }
        val caret =
            line.javaClass.declaredMethods
                .single { it.name.startsWith("caretX") }
                .apply { isAccessible = true }
        for (index in listOf(0, row.offsets.size / 2, row.offsets.lastIndex)) check(caret.invoke(line, row.offsets[index]) == row.positions[index])
        val extents =
            line.javaClass.declaredMethods
                .single { it.name.startsWith("caretExtents") }
                .apply { isAccessible = true }
        check(extents.invoke(line, row.offsets.first(), row.offsets.last()) == row.positions.min()..row.positions.max())
    }

    private fun rows(): List<Row> {
        val paragraphs = ArrayList<Triple<Int, Int, Int>>()
        var first = 0
        var offset = 0
        while (offset < value.length) {
            val scalar = value.codePointAt(offset)
            if (scalar in listOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)) {
                val next = if (scalar == 0x0D && offset + 1 < value.length && value[offset + 1] == '\n') offset + 2 else offset + 1
                paragraphs.add(Triple(first, offset, next))
                first = next
                offset = next
            } else {
                offset += Character.charCount(scalar)
            }
        }
        paragraphs.add(Triple(first, value.length, value.length))
        val result = paragraphs.flatMap { range -> paragraph(range.first, range.second, range.third) }
        val admitted = if (consumer === Consumer.TextArea) result else result.take(1 + (size.height - 1) / 11)
        return admitted.mapIndexed { index, row -> compact(row, index == admitted.lastIndex && admitted.size < result.size) }
    }

    private fun paragraph(
        start: Int,
        end: Int,
        nextStart: Int,
    ): List<Row> {
        val result = ArrayList<Row>()
        var offsets = arrayListOf(start)
        var prefixes = arrayListOf(0f)
        var offset = start
        while (offset < end) {
            val scalar = value.codePointAt(offset)
            var nextWidth = prefixes.last() + assets.advance(scalar, fontAt(offset))
            if (shape === Shape.Wrapped && width < assets.compatibility.roundedWidth(nextWidth) && 1 < offsets.size) {
                result.add(row(offsets, prefixes, offset))
                offsets = arrayListOf(offset)
                prefixes = arrayListOf(0f)
                nextWidth = assets.advance(scalar, fontAt(offset))
            }
            offset += Character.charCount(scalar)
            offsets.add(offset)
            prefixes.add(nextWidth)
        }
        result.add(row(offsets, prefixes, nextStart))
        return result
    }

    private fun row(
        offsets: List<Int>,
        prefixes: List<Float>,
        nextStart: Int,
    ): Row = Row(offsets.toIntArray(), prefixes.map(assets.compatibility::roundedWidth).toIntArray(), prefixes.toFloatArray(), nextStart)

    private fun compact(
        row: Row,
        heightOverflow: Boolean,
    ): Row {
        val ellipsis = shape === Shape.EllipsisFirst || shape === Shape.EllipsisMiddle || shape === Shape.EllipsisLast
        if (ellipsis.not() || (heightOverflow.not() && row.positions.last() <= width)) return row
        val last =
            row.offsets.indices.lastOrNull { index ->
                val previous = if (0 < index) row.offsets[index - 1] else row.offsets.first()
                val marker = assets.advance('.'.code, fontAt(previous))
                assets.compatibility.roundedWidth(((row.prefixes[index] + marker) + marker) + marker) <= width
            } ?: return row
        return Row(row.offsets.copyOf(last + 1), row.positions.copyOf(last + 1), row.prefixes.copyOf(last + 1), row.nextStart, true)
    }

    private fun glyphs(
        row: Row,
        index: Int,
    ): List<DrawCommand> {
        val offsets = row.offsets.dropLast(1)
        val visual = if (shape === Shape.DisplayOrder && consumer === Consumer.MultilineText) offsets.asReversed() else offsets
        val scalars = visual.map { offset -> value.codePointAt(offset) to fontAt(offset) }.toMutableList()
        if (row.ellipsis) repeat(3) { scalars.add('.'.code to fontAt(if (1 < row.offsets.size) row.offsets[row.offsets.lastIndex - 1] else row.offsets.first())) }
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val origin = if (consumer === Consumer.TextArea) 4 else 0
        var x = 0f
        return buildList {
            for ((scalar, font) in scalars) {
                val left = origin + x
                val top = (origin + index * 11).toFloat()
                add(DrawCommand.SampledImage(image, FloatRect(0.01f, 0.01f, 0.99f, 0.99f), FloatRect(left + 1f, top + 1f, left + 2f, top + 2f), ArgbColor(0xff383838.toInt())))
                add(DrawCommand.SampledImage(image, FloatRect(0.01f, 0.01f, 0.99f, 0.99f), FloatRect(left, top, left + 1f, top + 1f), ArgbColor(0xffe0e0e0.toInt())))
                x += assets.advance(scalar, font)
            }
        }
    }

    private fun literal(text: UiText): String =
        when (text) {
            is UiText.Literal -> text.value
            is UiText.WithFont -> literal(text.text)
            is UiText.Concatenated -> text.parts.joinToString("") { literal(it) }
            else -> error("The fixture admits only prepared literal/font inputs")
        }

    private data class Row(
        val offsets: IntArray,
        val positions: IntArray,
        val prefixes: FloatArray,
        val nextStart: Int,
        val ellipsis: Boolean = false,
    )
}
