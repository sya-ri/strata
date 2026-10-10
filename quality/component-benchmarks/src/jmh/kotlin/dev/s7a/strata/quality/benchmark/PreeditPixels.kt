package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent

/**
 * Independent complete 72 by 44 ARGB oracle for the frozen one-pixel font and unwrapped editor.
 * Uses scalar positions, explicit four-pixel padding, nine-pixel lines, caret following and focused extrema.
 * It consumes reference output, never real commands, runtime layouts, glyph runs or candidate normalization.
 * The frozen insertion offset remains separate from committed length after an external value update.
 *
 * @param insertion independently expected scalar boundary in the committed UTF-16 value.
 */
internal class PreeditPixels(
    private val committed: String,
    private val remaining: Int,
    private val advances: Map<Int, Int> = emptyMap(),
    private val insertion: Int = committed.length,
) {
    private var current: PreeditReference.Result? = null
    private var horizontal = 0
    private var vertical = 0

    init {
        require(insertion in 0..committed.length)
    }

    /**
     * Independent expected preedit outcome and presentation transition.
     */
    internal fun input(event: TextInputEvent.Preedit): InputResult {
        val normalized = PreeditReference.normalize(event, remaining) ?: return InputResult.Ignored
        val next = normalized.takeIf { it.text.isNotEmpty() }
        val textChanged = current?.text != next?.text
        val caretChanged = (current?.caret ?: 0) != (next?.caret ?: 0)
        current = next
        val layout = layout()
        val caret = checkNotNull(layout.positions[insertion + (current?.caret ?: 0)])
        val maximum = maxOf(0, maxOf(caret.x, layout.width) - 64 + 1)
        if (textChanged) {
            horizontal = horizontal.coerceIn(0, maximum)
            vertical = vertical.coerceIn(0, maxOf(0, layout.height - 36))
        }
        if (textChanged || caretChanged) {
            if (caret.y < vertical) vertical = caret.y
            if (vertical + 36 < caret.y + 9) vertical = caret.y + 9 - 36
            if (caret.x < horizontal) horizontal = caret.x
            if (horizontal + 64 <= caret.x) horizontal = caret.x - 64 + 1
        }
        horizontal = horizontal.coerceIn(0, maximum)
        return InputResult.Consumed
    }

    /**
     * Complete independently calculated image; every comparison covers all 3,168 physical pixels.
     */
    internal fun pixels(): IntArray {
        val pixels = IntArray(72 * 44) { 0xFF426789.toInt() }
        val layout = layout()
        fun fill(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            color: Int,
        ) {
            for (y in maxOf(4, top) until minOf(40, bottom)) {
                for (x in maxOf(4, left) until minOf(68, right)) pixels[y * 72 + x] = color
            }
        }
        layout.glyphs.forEach { position ->
            val x = 4 + position.x - horizontal
            val y = 4 + position.y - vertical
            fill(x, y, x + 1, y + 1, 0xFF404040.toInt())
        }
        underlines().forEach { fill(it.left, it.top, it.right, it.bottom, -1) }
        val caret = checkNotNull(layout.positions[insertion + (current?.caret ?: 0)])
        val x = caret.x - horizontal
        if (0 <= x && x <= 64) {
            val left = if (x == 64) 67 else x + 4
            fill(left, caret.y - vertical + 4, left + 1, caret.y - vertical + 13, -1)
        }
        return pixels
    }

    /**
     * Independent visible decoration rectangles for command-level geometry/extrema checks.
     */
    internal fun underlines(): List<IntRect> {
        val range = current?.focused?.takeIf { it.isEmpty().not() } ?: return emptyList()
        val start = insertion + range.first
        val end = insertion + range.last + 1
        return layout().lines.mapNotNull { line ->
            val first = maxOf(start, line.start)
            val last = minOf(end, line.end)
            if (last <= first) return@mapNotNull null
            val positions = line.boundaries.filter { it.first in first..last }.map { it.second }
            val left = (positions.minOrNull() ?: 0).minus(horizontal).coerceIn(0, 64) + 4
            val right = (positions.maxOrNull() ?: 0).minus(horizontal).coerceIn(0, 64) + 4
            val top = line.y + 12 - vertical
            if (left < right && top in 4 until 40) IntRect(left, top, right, top + 1) else null
        }
    }

    /**
     * Independently computed current vertical position exposed by the authoritative scroll state.
     */
    internal val scroll: Double get() = vertical.toDouble()

    private data class Position(
        val x: Int,
        val y: Int,
    )

    private data class Line(
        val start: Int,
        val end: Int,
        val y: Int,
        val boundaries: List<Pair<Int, Int>>,
    )

    private data class Layout(
        val positions: List<Position?>,
        val glyphs: List<Position>,
        val lines: List<Line>,
        val width: Int,
        val height: Int,
    )

    private fun layout(): Layout {
        val text = committed.substring(0, insertion) + current?.text.orEmpty() + committed.substring(insertion)
        val positions = MutableList<Position?>(text.length + 1) { null }
        val glyphs = ArrayList<Position>()
        val lines = ArrayList<Line>()
        var x = 0
        var y = 0
        var offset = 0
        var start = 0
        var maximum = 0
        var boundaries = arrayListOf(0 to 0)
        positions[0] = Position(0, 0)
        while (offset < text.length) {
            val scalar = text.codePointAt(offset)
            if (scalar == 10) {
                lines.add(Line(start, offset, y, boundaries.toList()))
                maximum = maxOf(maximum, x)
                offset++
                y += 9
                x = 0
                start = offset
                boundaries = arrayListOf(offset to 0)
            } else {
                glyphs.add(Position(x, y))
                x += advances[scalar] ?: 1
                offset += Character.charCount(scalar)
                boundaries.add(offset to x)
            }
            positions[offset] = Position(x, y)
        }
        lines.add(Line(start, offset, y, boundaries))
        return Layout(positions, glyphs, lines, maxOf(maximum, x), y + 9)
    }
}
