package dev.s7a.strata.runtime.minecraft

/**
 * Detached focused-composition spans derived from exactly one current layout, insertion offset, and normalized block range.
 *
 * Construction visits only current selected scalar boundaries, with memory bounded by current selected lines.
 * The editor replaces the result on range/content/reflow changes and drops it on composition loss, detach, or disposal.
 * Caret-only movement and frame changes retain it; wheel painting performs constant-time lookup per visible line.
 * No font service, original string, callback, historical layout, or application state is retained.
 *
 * @param firstLine first current visual line represented by [ranges].
 * @param ranges immutable private selected-line coordinate spans, including zero-width spans.
 */
internal class MinecraftTextAreaUnderlines private constructor(
    private val firstLine: Int,
    private val ranges: List<IntRange?>,
) {
    /**
     * Returns the selected scalar-boundary span for one current visual line.
     *
     * @param line current layout line index.
     * @return logical minimum and maximum x coordinates, or null outside the selected text range.
     */
    @JvmSynthetic
    internal fun bounds(line: Int): IntRange? = ranges.getOrNull(line - firstLine)

    /**
     * Constructs one bounded current composition result without retaining any inputs.
     */
    companion object {
        /**
         * Derives scalar-boundary extrema once before paint, including negative and cancelling advances.
         *
         * @param layout current detached composed text layout.
         * @param cursor committed canonical UTF-16 insertion position.
         * @param range optional canonical focused block range relative to the inserted preedit.
         * @return one current immutable result, or null without a nonempty focused block.
         */
        @JvmSynthetic
        internal fun create(
            layout: MinecraftTextLayout,
            cursor: Int,
            range: IntRange?,
        ): MinecraftTextAreaUnderlines? {
            if (range == null || range.isEmpty()) return null
            val start = Math.addExact(cursor, range.first)
            val end = Math.addExact(cursor, Math.addExact(range.last, 1))
            val firstLine = layout.lineAt(start)
            val lastLine = layout.lineAt(end, MinecraftTextCaretAffinity.Upstream)
            val ranges = ArrayList<IntRange?>(lastLine - firstLine + 1)
            for (index in firstLine..lastLine) {
                val line = layout.lines[index]
                val first = maxOf(start, line.start)
                val last = minOf(end, line.end)
                ranges.add(if (first < last) line.caretExtents(first, last) else null)
            }
            return MinecraftTextAreaUnderlines(firstLine, ranges)
        }
    }
}
