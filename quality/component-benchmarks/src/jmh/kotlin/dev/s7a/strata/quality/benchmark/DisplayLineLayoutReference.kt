package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Case
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Shape
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText

/**
 * Independent complete source/Float-range layout before applying any visible limit.
 * Reference rows and synthetic glyphs never borrow candidate arrays, methods or geometry.
 * A separate untimed private-helper probe reports actual range production; it is not per-input instrumentation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class DisplayLineLayoutReference(
    private val workload: Case,
    private val value: String,
    private val assets: TextLineLayoutAssets,
    private val size: IntSize,
    private val fontAt: (Int) -> ResourceId,
) {
    private val width = if (workload.consumer === Consumer.TextArea) size.width - 8 else size.width
    private val step = if (workload.consumer === Consumer.SingleLine) 9 else 11
    private val complete = completeRows()
    private val visibleLimit =
        if (workload.consumer !== Consumer.Display) {
            Int.MAX_VALUE
        } else {
            minOf(workload.policy.maxLines, if (size.height == Int.MAX_VALUE) Int.MAX_VALUE else if (size.height == 0) 0 else 1 + (size.height - 1) / step)
        }
    private val admitted = complete.take(visibleLimit)
    private val rows = admitted.mapIndexed { index, row -> compact(row, index == admitted.lastIndex && admitted.size < complete.size) }

    /**
     * Requires complete original content, native signed metrics, all scalar arrays, truncation and natural geometry.
     */
    internal fun verify(layout: Any) {
        check(TextAreaInputAccess.field(TextAreaInputAccess.field(layout, "content"), "value") == value)
        val lines = TextAreaInputAccess.lines(layout)
        check(lines.size == rows.size)
        check(TextAreaInputAccess.field(layout, "lineStep") == step)
        val truncated = admitted.size < complete.size || admitted.any { row -> width < assets.compatibility.roundedWidth(row.prefixes.last()) }
        check(TextAreaInputAccess.field(layout, "truncated") == truncated)
        val height = if (rows.isEmpty()) 0 else (rows.size - 1) * step + 9
        val naturalWidth = minOf(width, rows.maxOfOrNull(::runWidth) ?: 0)
        check(TextAreaInputAccess.field(layout, "size") == IntSize(naturalWidth, height))
        for ((line, row) in lines.zip(rows)) {
            check(TextAreaInputAccess.field(line, "start") == row.offsets.first())
            check(TextAreaInputAccess.field(line, "end") == row.offsets.last())
            check(TextAreaInputAccess.field(line, "nextStart") == row.nextStart)
            check((TextAreaInputAccess.field(line, "offsets") as IntArray).contentEquals(row.offsets))
            check((TextAreaInputAccess.field(line, "positions") as IntArray).contentEquals(row.prefixes.map(assets.compatibility::roundedWidth).toIntArray()))
            val run = TextAreaInputAccess.field(line, "run")
            check(literal(TextAreaInputAccess.field(run, "text") as UiText) == value.substring(row.offsets.first(), row.offsets.last()) + if (row.marker) "..." else "")
            check(TextAreaInputAccess.field(run, "nativeWidth") == nativeRunWidth(row))
        }
    }

    /**
     * Independently paints complete-reference visible glyphs with exact shadow ordering and clipping at three densities.
     * The retained fixture separately checks whole-frame immutability and full restored pixels.
     */
    internal fun verifyPixels(frame: RuntimeUiFrame) {
        val clip = if (workload.consumer === Consumer.TextArea) IntRect(4, 4, size.width - 4, size.height - 4) else IntRect(0, 0, size.width, size.height)
        val actual = buildList {
            add(DrawCommand.PushClip(clip))
            addAll(frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>())
            add(DrawCommand.PopClip)
        }
        val expected = buildList {
            add(DrawCommand.PushClip(clip))
            if (workload.shape !== Shape.Exceptional) rows.forEachIndexed { index, row -> addAll(glyphs(row, index)) }
            add(DrawCommand.PopClip)
        }
        val window = IntSize(minOf(64, size.width).coerceAtLeast(1), minOf(64, size.height).coerceAtLeast(1))
        for (scale in 1..3) {
            check(rasterizeHeadless(actual, window, scale).copyArgb().contentEquals(rasterizeHeadless(expected, window, scale).copyArgb()))
        }
    }

    /**
     * Invokes the actual private range helper outside timing with complete measured advances.
     * The original five-argument and bounded six-argument descriptors are decoded only at this external symbol adapter.
     */
    internal fun verifyRangeWork(owner: Any, layout: Any): Int {
        val renderer = if (workload.consumer === Consumer.TextArea) TextAreaInputAccess.field(TextAreaInputAccess.field(owner, "current"), "renderer") else TextAreaInputAccess.field(owner, "renderer")
        val type = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextLineBreaker")
        val engine = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val measure = type.declaredMethods.single { method -> method.name.startsWith("measure") }.apply { isAccessible = true }
        val content = TextAreaInputAccess.field(layout, "content")
        val advances = measure.invoke(engine, content, renderer) as FloatArray
        check(advances.size == value.length)
        var offset = 0
        while (offset < value.length) {
            val scalar = value.codePointAt(offset)
            check(advances[offset].toRawBits() == (if (hardBreak(scalar)) 0f else assets.advance(scalar, fontAt(offset))).toRawBits())
            offset += Character.charCount(scalar)
        }
        val ranges = type.declaredMethods.single { method -> method.name.startsWith("breakLines") }.apply { isAccessible = true }
        val limit = if (visibleLimit == Int.MAX_VALUE) Int.MAX_VALUE else visibleLimit + 1
        val arguments = arrayListOf<Any>(value, advances, renderer, width, workload.policy.wrap)
        val bounded = ranges.parameterCount == 6
        check(bounded || ranges.parameterCount == 5)
        if (bounded) arguments.add(limit)
        val actual = ranges.invoke(engine, *arguments.toTypedArray()) as List<*>
        check(actual.size == if (bounded) minOf(complete.size, limit) else complete.size)
        return actual.size
    }

    private fun completeRows(): List<Row> {
        if (workload.consumer === Consumer.SingleLine) return listOf(row(0, value.length, value.length))
        val result = ArrayList<Row>()
        var first = 0
        var offset = 0
        while (offset < value.length) {
            val scalar = value.codePointAt(offset)
            if (hardBreak(scalar)) {
                val next = if (scalar == 0x0D && offset + 1 < value.length && value[offset + 1] == '\n') offset + 2 else offset + 1
                result.addAll(paragraph(first, offset, next))
                first = next
                offset = next
            } else {
                offset += Character.charCount(scalar)
            }
        }
        result.addAll(paragraph(first, value.length, value.length))
        return result
    }

    private fun paragraph(start: Int, end: Int, nextStart: Int): List<Row> {
        if (start == end || workload.policy.wrap === TextWrap.None || width == Int.MAX_VALUE) return listOf(row(start, end, nextStart))
        val result = ArrayList<Row>()
        var first = start
        while (first < end) {
            var offset = first
            var prefix = 0f
            var wordEnd = first
            var last = end
            while (offset < end) {
                val scalar = value.codePointAt(offset)
                val next = offset + Character.charCount(scalar)
                val candidate = prefix + assets.advance(scalar, fontAt(offset))
                val overflow = width < assets.compatibility.roundedWidth(candidate)
                if (overflow && first < offset) {
                    last = if (workload.policy.wrap === TextWrap.Word && first < wordEnd) wordEnd else offset
                    break
                }
                prefix = candidate
                offset = next
                if (Character.isWhitespace(scalar) || scalar == 0x3000) wordEnd = offset
                if (overflow && workload.policy.wrap === TextWrap.Character) {
                    last = offset
                    break
                }
            }
            result.add(row(first, last, if (last == end) nextStart else last))
            first = last
        }
        return result
    }

    private fun row(start: Int, end: Int, nextStart: Int): Row {
        val offsets = ArrayList<Int>()
        val prefixes = ArrayList<Float>()
        offsets.add(start)
        prefixes.add(0f)
        var offset = start
        while (offset < end) {
            prefixes.add(prefixes.last() + assets.advance(value.codePointAt(offset), fontAt(offset)))
            offset += Character.charCount(value.codePointAt(offset))
            offsets.add(offset)
        }
        return Row(offsets.toIntArray(), prefixes.toFloatArray(), nextStart)
    }

    private fun compact(row: Row, moreLines: Boolean): Row {
        if (workload.consumer !== Consumer.Display || workload.policy.overflow !== TextOverflow.Ellipsis || (moreLines.not() && assets.compatibility.roundedWidth(row.prefixes.last()) <= width)) return row
        val end = row.offsets.indices.lastOrNull { index ->
            val marker = assets.advance('.'.code, markerFont(row, index))
            assets.compatibility.roundedWidth(((row.prefixes[index] + marker) + marker) + marker) <= width
        } ?: return row
        return Row(row.offsets.copyOf(end + 1), row.prefixes.copyOf(end + 1), row.nextStart, true)
    }

    private fun markerFont(row: Row, index: Int): ResourceId =
        when {
            0 < index -> fontAt(row.offsets[index - 1])
            row.offsets.first() < value.length -> fontAt(row.offsets.first())
            value.isNotEmpty() -> fontAt(value.offsetByCodePoints(value.length, -1))
            else -> fontAt(0)
        }

    private fun visual(row: Row): List<Pair<Int, ResourceId>> {
        val scalars = row.offsets.dropLast(1).map { offset -> value.codePointAt(offset) to fontAt(offset) }.toMutableList()
        if (row.marker) repeat(3) { scalars.add('.'.code to markerFont(row, row.offsets.lastIndex)) }
        return if (workload.shape === Shape.DisplayOrder && workload.consumer !== Consumer.TextArea) scalars.asReversed() else scalars
    }

    private fun nativeRunWidth(row: Row): Int {
        var width = 0f
        for ((scalar, font) in visual(row)) width += assets.advance(scalar, font)
        return assets.compatibility.roundedWidth(width)
    }

    private fun runWidth(row: Row): Int = nativeRunWidth(row).coerceAtLeast(0)

    private fun glyphs(row: Row, index: Int): List<DrawCommand> {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val origin = if (workload.consumer === Consumer.TextArea) 4 else 0
        var x = 0f
        return buildList {
            for ((scalar, font) in visual(row)) {
                val left = origin + x
                val top = (origin + index * step).toFloat()
                add(DrawCommand.SampledImage(image, FloatRect(0.01f, 0.01f, 0.99f, 0.99f), FloatRect(left + 1f, top + 1f, left + 2f, top + 2f), ArgbColor(0xff383838.toInt())))
                add(DrawCommand.SampledImage(image, FloatRect(0.01f, 0.01f, 0.99f, 0.99f), FloatRect(left, top, left + 1f, top + 1f), ArgbColor(0xffe0e0e0.toInt())))
                x += assets.advance(scalar, font)
            }
        }
    }

    private fun hardBreak(scalar: Int): Boolean = scalar in listOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)

    private fun literal(text: UiText): String =
        when (text) {
            is UiText.Literal -> text.value
            is UiText.WithFont -> literal(text.text)
            is UiText.Concatenated -> text.parts.joinToString("") { literal(it) }
            else -> error("Prepared literal/font source required")
        }

    private class Row(
        val offsets: IntArray,
        val prefixes: FloatArray,
        val nextStart: Int,
        val marker: Boolean = false,
    )
}
