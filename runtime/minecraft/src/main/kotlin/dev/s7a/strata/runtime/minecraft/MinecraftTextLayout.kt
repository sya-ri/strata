package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.PaintScope
import java.util.Collections
import kotlin.math.floor

/**
 * Immutable current-value line layout shared by display Text and editable TextArea.
 *
 * The result retains only this content, these lines, and detached glyph pixels; it owns no engine and keeps no history.
 * A component replaces the result when its text, font, style, wrap policy, or available width changes and clears it on disposal.
 * Logical caret positions use original scalar offsets even when display glyphs are shaped or reordered.
 *
 * @property content complete original text and font provenance.
 * @param lines visible lines in logical vertical order, snapshotted on construction.
 * @property size natural non-negative extent after line-count and width limits, before parent minimum constraints.
 * @property lineStep distance between consecutive nine-pixel line boxes.
 * @property truncated whether any source content was omitted or ellipsized.
 */
internal class MinecraftTextLayout(
    @get:JvmSynthetic
    internal val content: MinecraftTextContent,
    lines: List<MinecraftTextLine>,
    @get:JvmSynthetic
    internal val size: IntSize,
    @get:JvmSynthetic
    internal val lineStep: Int,
    @get:JvmSynthetic
    internal val truncated: Boolean,
) {
    /**
     * Read-only current lines; their runs contain no live font ownership.
     */
    @get:JvmSynthetic
    internal val lines: List<MinecraftTextLine> = Collections.unmodifiableList(lines.toList())
    private val verticalMetrics: MinecraftTextVerticalMetrics? =
        MinecraftTextVerticalMetrics
            .Builder()
            .also { builder ->
                lines.forEach { builder.add(it.run.verticalMetrics) }
            }.build()

    /**
     * Returns conservative bounds of every submitted line's quads, preserving natural font bearings and overhang.
     *
     * @return finite local union including shadows, or null when this layout paints no glyphs.
     */
    @JvmSynthetic
    internal fun inkBounds(): MinecraftTextInkBounds? {
        var result: MinecraftTextInkBounds? = null
        lines.forEachIndexed { index, line ->
            line.inkBounds?.let { bounds ->
                val y = Math.multiplyExact(index, lineStep).toDouble()
                val translated = MinecraftTextInkBounds(bounds.left, bounds.top + y, bounds.right, bounds.bottom + y)
                result = result?.union(translated) ?: translated
            }
        }
        return result
    }

    /**
     * Bounds candidate line visits by the viewport plus this current layout's maximum actual ink overhang.
     *
     * Two monotonic binary searches use the same integer-origin-to-float operation order as sampled painting.
     * Horizontal collapse at origin zero does not remove a potentially visible vertical candidate.
     * The caller checks each candidate's actual-origin vertical metrics before submitting glyphs.
     * Spacing-only layouts return no candidates, and caret or composition decorations are handled independently.
     *
     * @param top content-local inclusive viewport top after scrolling.
     * @param bottom content-local exclusive viewport bottom after scrolling.
     * @param originY scope-local origin of the first line, including padding and scrolling.
     * @return conservative line-index range; no historical viewport, string, or command cache is retained.
     * @throws IllegalArgumentException for non-finite viewport bounds.
     */
    @JvmSynthetic
    internal fun visibleLines(
        top: Double,
        bottom: Double,
        originY: Int = 0,
    ): IntRange {
        require(top.isFinite() && bottom.isFinite()) { "Text viewport coordinates must be finite." }
        val metrics = verticalMetrics ?: return IntRange.EMPTY
        if (bottom <= top || lines.isEmpty()) return IntRange.EMPTY
        val viewportTop = top + originY
        val viewportBottom = bottom + originY
        val first =
            firstLineMatching { index ->
                viewportTop < metrics.maximumBottomAt(originY.toLong() + index.toLong() * lineStep)
            }
        val end =
            firstLineMatching { index ->
                viewportBottom <= metrics.minimumTopAt(originY.toLong() + index.toLong() * lineStep)
            }
        return first until end
    }

    /**
     * Finds the line containing an original insertion offset.
     * Soft-wrap boundaries use [affinity]; the position before a hard break stays on its preceding line.
     *
     * @param offset possibly out-of-range original UTF-16 offset.
     * @param affinity whether a shared soft-wrap boundary belongs to the previous or following visual line.
     * @return zero-based line index, or zero when the viewport contains no lines.
     */
    @JvmSynthetic
    internal fun lineAt(
        offset: Int,
        affinity: MinecraftTextCaretAffinity = MinecraftTextCaretAffinity.Downstream,
    ): Int {
        val bounded = content.scalarBoundary(offset)
        var first = 0
        var end = lines.size
        while (first < end) {
            val middle = first + (end - first) / 2
            if (bounded < lines[middle].nextStart) end = middle else first = middle + 1
        }
        val index = minOf(first, maxOf(0, lines.lastIndex))
        if (affinity == MinecraftTextCaretAffinity.Upstream && 0 < index && lines[index].start == bounded) {
            val previous = lines[index - 1]
            if (previous.end == bounded && previous.nextStart == bounded) return index - 1
        }
        return index
    }

    /**
     * Returns the logical caret coordinate for an original scalar position.
     *
     * @param offset original UTF-16 insertion position.
     * @param affinity preferred visual side when this offset is shared by two soft-wrapped lines.
     * @return line-local horizontal coordinate and content-local vertical coordinate, or zero for an empty viewport.
     */
    @JvmSynthetic
    internal fun caretPosition(
        offset: Int,
        affinity: MinecraftTextCaretAffinity = MinecraftTextCaretAffinity.Downstream,
    ): IntOffset {
        if (lines.isEmpty()) return IntOffset.Zero
        val index = lineAt(offset, affinity)
        return IntOffset(lines[index].caretX(offset), Math.multiplyExact(index, lineStep))
    }

    /**
     * Finds the nearest logical nine-pixel line box, splitting any spacing gap at its midpoint.
     *
     * A gap midpoint belongs to the preceding line; coordinates above or below the document clamp to its ends.
     * This lookup uses logical line boxes, independently of glyph ink overhang.
     *
     * @param y finite content-local vertical coordinate after adding the viewport scroll offset.
     * @return zero-based visual line index, or zero for an empty layout.
     * @throws IllegalArgumentException when [y] is non-finite.
     */
    @JvmSynthetic
    internal fun lineIndexAt(y: Double): Int {
        require(y.isFinite()) { "Text pointer coordinates must be finite." }
        if (lines.isEmpty() || y <= 0.0) return 0
        val index = floor(y / lineStep).coerceIn(0.0, lines.lastIndex.toDouble()).toInt()
        if (index == lines.lastIndex) return index
        val local = y - index.toDouble() * lineStep
        return if ((9.0 + lineStep) / 2.0 < local) index + 1 else index
    }

    /**
     * Maps a content-local pointer to its nearest logical scalar boundary.
     *
     * @param x horizontal logical coordinate.
     * @param y vertical coordinate after adding the viewport's current scroll offset.
     * @return original UTF-16 insertion offset; no surrogate pair can be split.
     */
    @JvmSynthetic
    internal fun offsetAt(
        x: Int,
        y: Int,
    ): Int {
        if (lines.isEmpty()) return 0
        val line = lineIndexAt(y.toDouble())
        return lines[line].offsetAt(x)
    }

    /**
     * Paints only line and glyph candidates intersecting an already installed paint clip.
     *
     * Actual glyph geometry and shadow order remain unchanged; unbounded natural text uses [paint] instead.
     * Candidate indexes belong only to these current runs and retain no prior strings or viewport history.
     *
     * @param scope active owner-thread collector whose clip already contains [viewport].
     * @param viewport half-open clip in scope coordinates, including empty rectangles.
     * @param originX scope-local horizontal origin of each line.
     * @param originY scope-local vertical origin of the first line.
     * @return number of glyph candidates visited, for deterministic bounded-work verification.
     */
    @JvmSynthetic
    internal fun paintVisible(
        scope: PaintScope,
        viewport: IntRect,
        originX: Int = 0,
        originY: Int = 0,
    ): Int {
        if (viewport.width == 0 || viewport.height == 0) return 0
        var visited = 0
        val top = viewport.top.toDouble() - originY
        val bottom = viewport.bottom.toDouble() - originY
        for (index in visibleLines(top, bottom, originY)) {
            val run = lines[index].run
            val metrics = run.verticalMetrics
            val y = originY.toLong() + index.toLong() * lineStep
            if (metrics != null && viewport.top.toDouble() < metrics.maximumBottomAt(y) && metrics.minimumTopAt(y) < viewport.bottom.toDouble()) {
                visited = Math.addExact(visited, run.paintVisible(scope, originX, Math.toIntExact(y), viewport))
            }
        }
        return visited
    }

    private inline fun firstLineMatching(predicate: (Int) -> Boolean): Int {
        var first = 0
        var end = lines.size
        while (first < end) {
            val middle = first + (end - first) / 2
            if (predicate(middle)) end = middle else first = middle + 1
        }
        return first
    }

    /**
     * Paints a bounded range of detached lines into a caller-owned clip or viewport.
     *
     * @param scope active owner-thread paint collector.
     * @param originX text origin relative to the collector.
     * @param originY top of the first logical line, including the viewport scroll translation.
     * @param firstLine inclusive first line to submit.
     * @param endLine exclusive last line to submit.
     * @throws IllegalArgumentException when the requested line range is invalid.
     */
    @JvmSynthetic
    internal fun paint(
        scope: PaintScope,
        originX: Int = 0,
        originY: Int = 0,
        firstLine: Int = 0,
        endLine: Int = lines.size,
    ) {
        require(0 <= firstLine && firstLine <= endLine && endLine <= lines.size) { "Text paint ranges must name existing lines." }
        for (index in firstLine until endLine) {
            lines[index].run.paint(scope, originX, Math.addExact(originY, Math.multiplyExact(index, lineStep)))
        }
    }
}
