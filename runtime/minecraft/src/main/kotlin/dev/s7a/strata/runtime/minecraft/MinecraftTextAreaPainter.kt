package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope

/**
 * Stateless multiline-editor paint operations executed inside the portable padded clip.
 *
 * Candidate line visits are bounded by the current viewport plus the current layout's maximum actual ink overhang.
 * Each candidate's actual translated ink must intersect the viewport before its detached run is submitted.
 * Glyph rectangles and source coordinates are never cropped before final-density sampling.
 * An exact-fit insertion position at the viewport's exclusive right edge paints its caret on the final inside pixel; logical metrics and glyphs remain unchanged.
 * The painter borrows callback-lifetime scopes and retains no commands, layout, state, or renderer after returning.
 */
internal object MinecraftTextAreaPainter {
    /**
     * Paints visible detached lines followed by canonical caret and focused-composition decoration.
     *
     * @param scope active outer-node paint scope with its padded text clip already installed.
     * @param layout complete current composed layout.
     * @param settings borrowed owner-thread editor settings.
     * @param cursor committed canonical UTF-16 insertion offset.
     * @param preedit optional normalized uncommitted composition.
     * @param focused whether the editor owns input focus.
     * @param horizontalOffset non-negative internal horizontal displacement for unwrapped caret following.
     * @param affinity visual edge of the displayed insertion position at a soft-wrap boundary.
     * @param underlines precomputed current focused-block spans; painting never scans the block's complete glyph sequence.
     */
    @JvmSynthetic
    internal fun paint(
        scope: PaintScope,
        layout: MinecraftTextLayout,
        settings: MinecraftTextAreaConfiguration,
        cursor: Int,
        preedit: MinecraftTextAreaPreedit?,
        focused: Boolean,
        horizontalOffset: Int = 0,
        affinity: MinecraftTextCaretAffinity = MinecraftTextCaretAffinity.Downstream,
        underlines: MinecraftTextAreaUnderlines? = null,
    ) {
        val verticalOffset =
            settings.state.scrollState.metrics.offset
                .toInt()
        paintLines(scope, layout, settings.innerSize, horizontalOffset, verticalOffset)
        if (focused && settings.enabled) {
            val decoration = Decoration(scope, layout, settings.innerSize, horizontalOffset, verticalOffset)
            decoration.preedit(underlines)
            decoration.caret(Math.addExact(cursor, preedit?.caretPosition ?: 0), affinity)
        }
    }

    private fun paintLines(
        scope: PaintScope,
        layout: MinecraftTextLayout,
        viewport: IntSize,
        horizontalOffset: Int,
        verticalOffset: Int,
    ) {
        layout.paintVisible(
            scope,
            IntRect(4, 4, viewport.width + 4, viewport.height + 4),
            Math.subtractExact(4, horizontalOffset),
            Math.subtractExact(4, verticalOffset),
        )
    }

    private class Decoration(
        private val scope: PaintScope,
        private val layout: MinecraftTextLayout,
        private val viewport: IntSize,
        private val horizontalOffset: Int,
        private val verticalOffset: Int,
    ) {
        private val color = ArgbColor(-1)

        fun caret(
            offset: Int,
            affinity: MinecraftTextCaretAffinity,
        ) {
            val position = layout.caretPosition(offset, affinity)
            val logicalLeft = position.x.toLong() - horizontalOffset
            val left = if (logicalLeft == viewport.width.toLong()) logicalLeft - 1L else logicalLeft
            val top = position.y.toLong() - verticalOffset
            val bottom = top + 9L
            if (left < 0L || viewport.width.toLong() <= left) return
            if (bottom <= 0L || viewport.height.toLong() <= top) return
            scope.fillRectangle(
                IntRect(
                    Math.toIntExact(left + 4L),
                    Math.toIntExact(maxOf(0L, top) + 4L),
                    Math.toIntExact(left + 5L),
                    Math.toIntExact(minOf(viewport.height.toLong(), bottom) + 4L),
                ),
                color,
            )
        }

        fun preedit(underlines: MinecraftTextAreaUnderlines?) {
            if (underlines == null) return
            val firstLine = Math.floorDiv(verticalOffset.toLong() - 8L + layout.lineStep - 1L, layout.lineStep.toLong()).coerceAtLeast(0L).toInt()
            val lastLine = Math.floorDiv(verticalOffset.toLong() + viewport.height - 9L, layout.lineStep.toLong()).coerceAtMost(layout.lines.lastIndex.toLong()).toInt()
            for (index in firstLine..lastLine) {
                val range = underlines.bounds(index) ?: continue
                val top = index.toLong() * layout.lineStep - verticalOffset + 8L
                val left = (range.first.toLong() - horizontalOffset).coerceIn(0L, viewport.width.toLong()).toInt()
                val right = (range.last.toLong() - horizontalOffset).coerceIn(0L, viewport.width.toLong()).toInt()
                if (left < right) scope.fillRectangle(IntRect(left + 4, top.toInt() + 4, right + 4, top.toInt() + 5), color)
            }
        }
    }
}
