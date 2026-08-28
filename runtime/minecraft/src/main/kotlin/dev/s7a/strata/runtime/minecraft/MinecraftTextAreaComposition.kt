package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.input.TextInputEvent

/**
 * Stateless bounded canonicalization for one committed scalar or one multiline IME update.
 *
 * Every operation runs on the input owner thread and retains no text after returning.
 * Invalid scalar, control, or over-budget input is rejected before the editor changes its cursor, value, scroll, or composition.
 */
internal object MinecraftTextAreaComposition {
    /**
     * Converts an accepted scalar to its canonical committed text.
     *
     * @param codePoint decoded scalar from the platform text callback.
     * @return LF for a mandatory hard break, the original scalar otherwise, or null for unsupported input.
     */
    @JvmSynthetic
    internal fun committed(codePoint: Int): String? =
        when {
            accepted(codePoint).not() -> null
            MinecraftTextContent.isHardBreak(codePoint) -> "\n"
            else -> String(Character.toChars(codePoint))
        }

    /**
     * Normalizes one current composition within the space remaining beside the committed state.
     *
     * CRLF split across matching blocks is one LF, with an intermediate caret mapping after that LF.
     * Blocks are inspected without copying or normalizing their text; only a matching focused range is retained.
     * Raw text and total block text are bounded by twice [remainingUtf16], the maximum CRLF contraction factor.
     * Block count is bounded by the raw text budget plus one, including empty boundary blocks.
     * A caret or block boundary that splits a surrogate pair is rejected.
     *
     * @param event immutable uncommitted input-method update.
     * @param remainingUtf16 non-negative normalized UTF-16 capacity after the committed value.
     * @return budgeted canonical update, including empty text to clear composition, or null for invalid input.
     * @throws IllegalArgumentException when the remaining capacity is negative.
     */
    @JvmSynthetic
    internal fun normalize(
        event: TextInputEvent.Preedit,
        remainingUtf16: Int,
    ): MinecraftTextAreaPreedit? {
        require(0 <= remainingUtf16) { "TextArea composition capacity must be non-negative." }
        val rawBudget = remainingUtf16.toLong() * 2L
        if (rawBudget < event.fullText.length || rawBudget + 1L < event.blocks.size) return null
        val blocks = inspectBlocks(event, rawBudget) ?: return null
        return Canonicalizer(event, blocks.focused, remainingUtf16).normalize()
    }

    private fun inspectBlocks(
        event: TextInputEvent.Preedit,
        rawBudget: Long,
    ): Blocks? {
        var offset = 0L
        var agrees = true
        var focused: IntRange? = null
        event.blocks.forEachIndexed { index, block ->
            val end = offset + block.length
            if (rawBudget < end || Int.MAX_VALUE < end || validBlock(block).not()) return null
            if (event.fullText.regionMatches(offset.toInt(), block, 0, block.length).not()) agrees = false
            if (index == event.focusedBlock) focused = offset.toInt() until end.toInt()
            offset = end
        }
        return Blocks(focused.takeIf { agrees && offset == event.fullText.length.toLong() })
    }

    private fun validBlock(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (accepted(codePoint).not()) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun accepted(codePoint: Int): Boolean =
        when {
            (codePoint in 0..0x10FFFF).not() -> false
            codePoint in 0xD800..0xDFFF -> false
            MinecraftTextContent.isHardBreak(codePoint) -> true
            else -> 0x20 <= codePoint && codePoint != 0x7F && codePoint != 0xA7
        }

    private data class Blocks(
        val focused: IntRange?,
    )

    private class Canonicalizer(
        private val event: TextInputEvent.Preedit,
        private val focused: IntRange?,
        private val capacity: Int,
    ) {
        private val text = StringBuilder(minOf(event.fullText.length, capacity))
        private var offset = 0
        private var caret: Int? = null
        private var first: Int? = null
        private var end: Int? = null

        fun normalize(): MinecraftTextAreaPreedit? {
            recordBoundary()
            while (offset < event.fullText.length) {
                val codePoint = event.fullText.codePointAt(offset)
                if (accepted(codePoint).not()) return null
                val isBreak = MinecraftTextContent.isHardBreak(codePoint)
                val units = if (isBreak) 1 else Character.charCount(codePoint)
                if (capacity - text.length < units) return null
                if (isBreak) {
                    text.append('\n')
                    offset++
                    recordBoundary()
                    if (codePoint == 0x0D && offset < event.fullText.length && event.fullText[offset] == '\n') offset++
                } else {
                    text.appendCodePoint(codePoint)
                    offset += units
                }
                recordBoundary()
            }
            val caretPosition = caret ?: return null
            val range =
                if (focused == null) {
                    null
                } else {
                    (first ?: return null) until (end ?: return null)
                }
            return MinecraftTextAreaPreedit(text.toString(), caretPosition, range)
        }

        private fun recordBoundary() {
            if (offset == event.caretPosition) caret = text.length
            if (focused?.first == offset) first = text.length
            if (focused != null && focused.last.toLong() + 1L == offset.toLong()) end = text.length
        }
    }
}
