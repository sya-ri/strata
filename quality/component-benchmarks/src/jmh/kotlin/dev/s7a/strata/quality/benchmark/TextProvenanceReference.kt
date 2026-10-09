package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Consumer
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Independent original dense provenance, full scalar wrapping, Float-prefix and font-preserving slice oracle.
 * Expected fonts and line decisions never read candidate content, offsets, lookup or range construction.
 * Complete ordered submissions include viewport clipping, original geometry, image pixels and editor decoration before bounded raster controls.
 */
@Suppress("TooManyFunctions") // Independent layout, full-command and raster checks share only prepared reference rows.
@OptIn(InternalStrataRuntimeApi::class)
internal class TextProvenanceReference(
    private val content: TextProvenanceDenseReference,
    private val assets: TextProvenanceAssets,
    private val consumer: Consumer,
    private val policy: TextLayout.Multiline,
    private val viewport: IntSize,
) {
    private val width = if (consumer === Consumer.TextArea) viewport.width - 8 else viewport.width
    private val rows = if (consumer === Consumer.SingleLineText) listOf(row(0, content.value.length, content.value.length)) else rows()

    /**
     * Requires complete original text/range/nextStart/caret arrays and exact independent native glyph-run widths.
     */
    internal fun verify(presentation: Any) {
        if (consumer === Consumer.SingleLineText) {
            check(field(presentation, "text") == content.text)
            check(field(presentation, "nativeWidth") == nativeWidth(rows.single()))
            return
        }
        val actualContent = field(presentation, "content")
        check(field(actualContent, "text") == content.text)
        check(field(actualContent, "value") == content.value)
        val lines = (field(presentation, "lines") as List<*>).map(::checkNotNull)
        check(lines.size == rows.size)
        for ((line, row) in lines.zip(rows)) {
            check(field(line, "start") == row.offsets.first())
            check(field(line, "end") == row.offsets.last())
            check(field(line, "nextStart") == row.nextStart)
            check((field(line, "offsets") as IntArray).contentEquals(row.offsets))
            check((field(line, "positions") as IntArray).contentEquals(row.positions))
            val run = field(line, "run")
            check(field(run, "text") == row.text)
            check(field(run, "nativeWidth") == nativeWidth(row))
        }
        check(field(presentation, "lineStep") == 9)
        val height = if (rows.isEmpty()) 0 else Math.addExact(Math.multiplyExact(rows.size - 1, 9), 9)
        val expectedWidth = minOf(width, rows.maxOfOrNull { maxOf(0, nativeWidth(it)) } ?: 0)
        check(field(presentation, "size") == IntSize(expectedWidth, height))
    }

    /**
     * Compares the complete original viewport submission, including commands beyond the raster control window.
     * Bounds, glyphs, source images and decorations come from the frozen inputs and independent scalar rows.
     * The caller supplies its declared editing cursor and public scroll value; actual glyph commands never form expected output.
     */
    internal fun verifyFrame(
        frame: RuntimeUiFrame,
        owner: Any,
        scrollCell: Int,
        committedCaret: Int,
        composed: Boolean,
    ) {
        val expectedSize = if (consumer === Consumer.SingleLineText) IntSize(maxOf(0, nativeWidth(rows.single())), 9) else viewport
        check(frame.size == expectedSize)
        val editor = consumer === Consumer.TextArea
        if (editor) check(field(field(owner, "viewport"), "horizontalOffset") == 0)
        val bounds = if (editor) IntRect(4, 4, viewport.width - 4, viewport.height - 4) else IntRect(0, 0, viewport.width, viewport.height)
        val originX = if (editor) 4 else 0
        val originY = if (editor) 4 - scrollCell else 0
        val allGlyphs = rows.flatMapIndexed { index, row -> glyphs(row, originX, Math.addExact(originY, Math.multiplyExact(index, 9))) }
        val submitted =
            if (consumer === Consumer.SingleLineText) {
                allGlyphs
            } else {
                allGlyphs.filter { command ->
                    val rectangle = command.destination
                    bounds.left.toDouble() < rectangle.right && rectangle.left.toDouble() < bounds.right &&
                        bounds.top.toDouble() < rectangle.bottom && rectangle.top.toDouble() < bounds.bottom
                }
            }
        val expectedFrame =
            buildList {
                if (editor) addAll(editorFrame())
                if (consumer !== Consumer.SingleLineText) add(DrawCommand.PushClip(bounds))
                addAll(submitted)
                if (editor) addAll(editorDecoration(committedCaret, composed, originY))
                if (consumer !== Consumer.SingleLineText) add(DrawCommand.PopClip)
            }
        check(matchingCommands(expectedFrame, frame.drawCommands))
        val window = IntSize(minOf(64, viewport.width), minOf(40, viewport.height))
        verifyCommandGuard(expectedFrame, window)
        val expected =
            buildList {
                add(DrawCommand.PushClip(bounds))
                addAll(allGlyphs)
                add(DrawCommand.PopClip)
            }
        val actual =
            buildList {
                add(DrawCommand.PushClip(bounds))
                addAll(frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>())
                add(DrawCommand.PopClip)
            }
        for (density in 1..3) {
            check(rasterizeHeadless(expected, window, density).copyArgb().contentEquals(rasterizeHeadless(actual, window, density).copyArgb()))
        }
    }

    private fun editorFrame(): List<DrawCommand.BlitImage> {
        val image = createDrawImage(IntSize(200, 20), IntArray(4000) { 0xff426789.toInt() })
        val sourceX = listOf(0, 1, 199, 200)
        val sourceY = listOf(0, 1, 19, 20)
        val destinationX = listOf(0, 1, viewport.width - 1, viewport.width)
        val destinationY = listOf(0, 1, viewport.height - 1, viewport.height)
        return buildList {
            for (row in 0..2) {
                for (column in 0..2) {
                    val sourceWidth = sourceX[column + 1] - sourceX[column]
                    val sourceHeight = sourceY[row + 1] - sourceY[row]
                    val tileWidth = if (sourceWidth == 1) destinationX[column + 1] - destinationX[column] else sourceWidth
                    val tileHeight = if (sourceHeight == 1) destinationY[row + 1] - destinationY[row] else sourceHeight
                    var top = destinationY[row]
                    while (top < destinationY[row + 1]) {
                        val height = minOf(tileHeight, destinationY[row + 1] - top)
                        var left = destinationX[column]
                        while (left < destinationX[column + 1]) {
                            val width = minOf(tileWidth, destinationX[column + 1] - left)
                            add(
                                DrawCommand.BlitImage(
                                    image,
                                    IntRect(sourceX[column], sourceY[row], sourceX[column] + minOf(sourceWidth, width), sourceY[row] + minOf(sourceHeight, height)),
                                    IntRect(left, top, left + width, top + height),
                                ),
                            )
                            left += tileWidth
                        }
                        top += tileHeight
                    }
                }
            }
        }
    }

    private fun editorDecoration(
        committedCaret: Int,
        composed: Boolean,
        originY: Int,
    ): List<DrawCommand.FillRectangle> =
        buildList {
            if (composed) {
                val focusedEnd = rows.first().positions[rows.first().offsets.indexOf(2)]
                val top = originY + 8
                if (4 <= top && top < viewport.height - 4 && 0 < focusedEnd) {
                    add(DrawCommand.FillRectangle(IntRect(4, top, minOf(viewport.width - 4, focusedEnd + 4), top + 1), ArgbColor(-1)))
                }
            }
            val caret = Math.addExact(committedCaret, if (composed) 2 else 0)
            val index = rows.indexOfLast { it.offsets.first() <= caret }.coerceAtLeast(0)
            val row = rows[index]
            val position = row.positions[row.offsets.indexOf(caret.coerceIn(row.offsets.first(), row.offsets.last()))]
            val left = if (position == viewport.width - 8) position + 3 else position + 4
            val top = Math.addExact(originY, Math.multiplyExact(index, 9))
            if (4 <= left && left < viewport.width - 4 && top < viewport.height - 4 && 4 < top + 9) {
                add(DrawCommand.FillRectangle(IntRect(left, maxOf(4, top), left + 1, minOf(viewport.height - 4, top + 9)), ArgbColor(-1)))
            }
        }

    private fun matchingCommands(
        expected: List<DrawCommand>,
        actual: List<DrawCommand>,
    ): Boolean =
        expected.size == actual.size &&
            expected.zip(actual).all { (before, after) ->
                when (before) {
                    is DrawCommand.SampledImage ->
                        after is DrawCommand.SampledImage &&
                            before.source == after.source && before.destination == after.destination &&
                            before.tint == after.tint && before.alphaCutoff == after.alphaCutoff && before.orientation == after.orientation &&
                            matchingImages(before.image, after.image)
                    is DrawCommand.BlitImage ->
                        after is DrawCommand.BlitImage && before.source == after.source && before.destination == after.destination &&
                            matchingImages(before.image, after.image)
                    is DrawCommand.FillRectangle -> after is DrawCommand.FillRectangle && before.bounds == after.bounds && before.color == after.color
                    is DrawCommand.PushClip -> after is DrawCommand.PushClip && before.bounds == after.bounds
                    DrawCommand.PopClip -> after === DrawCommand.PopClip
                    else -> false
                }
            }

    private fun matchingImages(
        expected: DrawImage,
        actual: DrawImage,
    ): Boolean = expected.size == actual.size && expected.copyArgb().contentEquals(actual.copyArgb())

    private fun verifyCommandGuard(
        expected: List<DrawCommand>,
        window: IntSize,
    ) {
        val index = expected.indexOfFirst { it is DrawCommand.SampledImage && (window.width <= it.destination.left || window.height <= it.destination.top) }
        if (index < 0) return
        val omitted = expected.toMutableList().also { it.removeAt(index) }
        check(rasterizeHeadless(expected, window).copyArgb().contentEquals(rasterizeHeadless(omitted, window).copyArgb()))
        check(matchingCommands(expected, omitted).not())
    }

    private fun rows(): List<Row> {
        val complete = ArrayList<Row>()
        var first = 0
        var offset = 0
        while (offset < content.value.length) {
            val scalar = content.value.codePointAt(offset)
            if (scalar in listOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)) {
                val next = if (scalar == 0x0D && offset + 1 < content.value.length && content.value[offset + 1] == '\n') offset + 2 else offset + 1
                paragraph(first, offset, next, complete)
                first = next
                offset = next
            } else {
                offset += Character.charCount(scalar)
            }
        }
        paragraph(first, content.value.length, content.value.length, complete)
        val heightLimit = if (consumer === Consumer.TextArea) Int.MAX_VALUE else 1 + (viewport.height - 1) / 9
        val admitted = complete.take(minOf(policy.maxLines, heightLimit))
        return admitted.mapIndexed { index, row -> compact(row, index == admitted.lastIndex && admitted.size < complete.size) }
    }

    private fun paragraph(
        start: Int,
        end: Int,
        nextStart: Int,
        output: MutableList<Row>,
    ) {
        if (start == end) {
            output.add(row(start, end, nextStart))
            return
        }
        var first = start
        while (first < end) {
            var offset = first
            var advance = 0f
            while (offset < end) {
                val scalar = content.value.codePointAt(offset)
                val nextWidth = advance + assets.advance(content.fontAt(offset), scalar)
                if (width < assets.compatibility.roundedWidth(nextWidth) && first < offset) break
                advance = nextWidth
                offset += Character.charCount(scalar)
                if (width < assets.compatibility.roundedWidth(nextWidth)) break
            }
            output.add(row(first, offset, if (offset == end) nextStart else offset))
            first = offset
        }
    }

    private fun row(
        start: Int,
        end: Int,
        nextStart: Int,
    ): Row {
        val offsets = ArrayList<Int>()
        val advances = ArrayList<Float>()
        offsets.add(start)
        advances.add(0f)
        var offset = start
        while (offset < end) {
            val scalar = content.value.codePointAt(offset)
            advances.add(advances.last() + assets.advance(content.fontAt(offset), scalar))
            offset += Character.charCount(scalar)
            offsets.add(offset)
        }
        return Row(offsets.toIntArray(), advances.toFloatArray(), nextStart, content.slice(start, end))
    }

    private fun compact(
        original: Row,
        heightOverflow: Boolean,
    ): Row {
        val overflow = width < assets.compatibility.roundedWidth(original.advances.last()) || heightOverflow
        if (policy.overflow !== TextOverflow.Ellipsis || overflow.not()) return original
        val last =
            original.offsets.indices.lastOrNull { index ->
                val font = markerFont(original.offsets.first(), original.offsets[index])
                val marker = assets.advance(font, '.'.code)
                assets.compatibility.roundedWidth(((original.advances[index] + marker) + marker) + marker) <= width
            } ?: return original
        val first = original.offsets.first()
        val end = original.offsets[last]
        val text = TextProvenanceDenseReference.create(UiText.concat(content.slice(first, end), UiText.Literal("...").withFont(markerFont(first, end)))).let { it.slice(0, it.value.length) }
        return Row(original.offsets.copyOf(last + 1), original.advances.copyOf(last + 1), original.nextStart, text)
    }

    private fun markerFont(
        first: Int,
        end: Int,
    ): ResourceId =
        when {
            first < end -> content.fontAt(content.value.offsetByCodePoints(end, -1))
            first < content.value.length -> content.fontAt(first)
            content.value.isNotEmpty() -> content.fontAt(content.value.offsetByCodePoints(content.value.length, -1))
            else -> content.fontAt(0)
        }

    private fun nativeWidth(row: Row): Int {
        val run = TextProvenanceDenseReference.create(row.text)
        var offset = 0
        var width = 0f
        while (offset < run.value.length) {
            val scalar = run.value.codePointAt(offset)
            width += assets.advance(run.fontAt(offset), scalar)
            offset += Character.charCount(scalar)
        }
        return assets.compatibility.roundedWidth(width)
    }

    private fun glyphs(
        row: Row,
        originX: Int,
        originY: Int,
    ): List<DrawCommand.SampledImage> {
        val run = TextProvenanceDenseReference.create(row.text)
        val visual =
            if (consumer === Consumer.TextArea) {
                buildList {
                    var offset = 0
                    while (offset < run.value.length) {
                        add(MinecraftVisualGlyph(run.value.codePointAt(offset), offset))
                        offset += Character.charCount(run.value.codePointAt(offset))
                    }
                }
            } else {
                assets.visual(run.value)
            }
        var cursor = 0f
        return buildList {
            for (glyph in visual) {
                val layers = if (consumer === Consumer.TextArea) listOf(true, false) else listOf(false)
                for (shadow in layers) {
                    val offset = if (shadow) 1f else 0f
                    val left = originX + cursor + offset
                    val top = originY + offset
                    val right = (originX + cursor + 1f) + offset
                    val bottom = (originY + 1f) + offset
                    if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left < right && top < bottom && (right - left).isFinite() && (bottom - top).isFinite()) {
                        val tint = if (shadow) 0xff383838.toInt() else if (consumer === Consumer.TextArea) 0xffe0e0e0.toInt() else 0xff404040.toInt()
                        add(DrawCommand.SampledImage(assets.image, FloatRect(0.01f, 0.01f, 0.99f, 0.99f), FloatRect(left, top, right, bottom), ArgbColor(tint)))
                    }
                }
                cursor += assets.advance(run.fontAt(glyph.sourceIndex), glyph.codePoint)
            }
        }
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any = TextProvenanceOwnerAccess.field(owner, name)

    private inner class Row(
        val offsets: IntArray,
        val advances: FloatArray,
        val nextStart: Int,
        val text: UiText,
    ) {
        val positions: IntArray = advances.map(assets.compatibility::roundedWidth).toIntArray()
    }
}
