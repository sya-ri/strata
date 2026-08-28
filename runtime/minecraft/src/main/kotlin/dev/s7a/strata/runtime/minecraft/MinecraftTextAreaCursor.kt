package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent

/**
 * Owner-thread scalar insertion position and preferred horizontal position for vertical navigation.
 *
 * The cursor owns no text or rendering resources and retains no selection or navigation history.
 * Its offsets always refer to the caller state's canonical UTF-16 value.
 *
 * @param initialOffset initial insertion offset, normally the initial value length.
 */
internal class MinecraftTextAreaCursor(
    initialOffset: Int,
) {
    /**
     * Current canonical UTF-16 insertion offset.
     */
    @get:JvmSynthetic
    internal var offset: Int = initialOffset
        private set

    /**
     * Preferred visual edge at a soft-wrap boundary; no historical layout or line index is retained.
     */
    @get:JvmSynthetic
    internal var affinity: MinecraftTextCaretAffinity = MinecraftTextCaretAffinity.Downstream
        private set

    private var preferredX: Int? = null

    /**
     * Moves to a preceding scalar boundary and optionally preserves vertical navigation's preferred x position.
     *
     * @param value current canonical text.
     * @param next requested insertion offset.
     * @param preserveColumn retains the x position across repeated vertical moves.
     * @param affinity visual side when the new position is a soft-wrap boundary.
     */
    @JvmSynthetic
    internal fun move(
        value: String,
        next: Int,
        preserveColumn: Boolean = false,
        affinity: MinecraftTextCaretAffinity = MinecraftTextCaretAffinity.Downstream,
    ) {
        val bounded = next.coerceIn(0, value.length)
        offset = if (0 < bounded && bounded < value.length && value[bounded].isLowSurrogate()) bounded - 1 else bounded
        this.affinity = affinity
        if (preserveColumn.not()) preferredX = null
    }

    /**
     * Applies one supported navigation key to the current committed layout.
     *
     * @param event owner-thread key press.
     * @param layout current logical-order committed layout.
     * @param viewportHeight positive inner viewport height used for page movement.
     * @return true when this was a navigation key, including a no-op at a document boundary.
     */
    @JvmSynthetic
    internal fun navigate(
        event: KeyboardEvent.Press,
        layout: MinecraftTextLayout,
        viewportHeight: Int,
    ): Boolean =
        when (event.key) {
            KeyCode.Left, KeyCode.Right -> horizontal(layout, event.key)
            KeyCode.Home, KeyCode.End -> boundary(layout, event.key, event.modifiers.control || event.modifiers.superKey)
            KeyCode.Up -> vertical(layout, -1)
            KeyCode.Down -> vertical(layout, 1)
            KeyCode.PageUp -> vertical(layout, -maxOf(1, viewportHeight / layout.lineStep))
            KeyCode.PageDown -> vertical(layout, maxOf(1, viewportHeight / layout.lineStep))
            else -> false
        }

    private fun boundary(
        layout: MinecraftTextLayout,
        key: KeyCode,
        document: Boolean,
    ): Boolean {
        val value = layout.content.value
        val currentLine = layout.lineAt(offset, affinity)
        val line = layout.lines[currentLine]
        val next =
            when (key) {
                KeyCode.Home -> if (document) 0 else line.start
                KeyCode.End -> if (document) value.length else line.end
                else -> return false
            }
        val sharedEnd = next == line.end && next == line.nextStart && currentLine < layout.lines.lastIndex
        move(value, next, affinity = if (key == KeyCode.End && document.not() && sharedEnd) MinecraftTextCaretAffinity.Upstream else MinecraftTextCaretAffinity.Downstream)
        return true
    }

    /**
     * Selects a scalar on one current visual line and preserves that line's end rather than jumping to the next soft line.
     *
     * @param layout current immutable composed or committed layout.
     * @param index valid current line index.
     * @param x logical horizontal target, including any horizontal viewport pan.
     * @param preserveColumn retains a vertical navigation sequence's preferred x coordinate.
     */
    @JvmSynthetic
    internal fun moveToLine(
        layout: MinecraftTextLayout,
        index: Int,
        x: Int,
        preserveColumn: Boolean = false,
    ) {
        val line = layout.lines[index]
        val next = line.offsetAt(x)
        val affinity =
            if (next == line.end && next == line.nextStart && index < layout.lines.lastIndex) MinecraftTextCaretAffinity.Upstream else MinecraftTextCaretAffinity.Downstream
        move(layout.content.value, next, preserveColumn, affinity)
    }

    private fun vertical(
        layout: MinecraftTextLayout,
        delta: Int,
    ): Boolean {
        val current = layout.lineAt(offset, affinity)
        val x = preferredX ?: layout.lines[current].caretX(offset)
        preferredX = x
        val next = (current.toLong() + delta).coerceIn(0L, layout.lines.lastIndex.toLong()).toInt()
        moveToLine(layout, next, x, preserveColumn = true)
        return true
    }

    private fun horizontal(
        layout: MinecraftTextLayout,
        key: KeyCode,
    ): Boolean {
        val upstream = layout.lineAt(offset, MinecraftTextCaretAffinity.Upstream)
        val downstream = layout.lineAt(offset, MinecraftTextCaretAffinity.Downstream)
        val left = key == KeyCode.Left
        val targetAffinity = if (left) MinecraftTextCaretAffinity.Upstream else MinecraftTextCaretAffinity.Downstream
        if (upstream != downstream && affinity != targetAffinity) {
            move(layout.content.value, offset, affinity = targetAffinity)
            return true
        }
        val value = layout.content.value
        val next =
            if (left) {
                if (offset == 0) 0 else value.offsetByCodePoints(offset, -1)
            } else {
                if (offset == value.length) offset else value.offsetByCodePoints(offset, 1)
            }
        val shared = layout.lineAt(next, MinecraftTextCaretAffinity.Upstream) != layout.lineAt(next, MinecraftTextCaretAffinity.Downstream)
        move(value, next, affinity = if (left.not() && shared) MinecraftTextCaretAffinity.Upstream else MinecraftTextCaretAffinity.Downstream)
        return true
    }
}
