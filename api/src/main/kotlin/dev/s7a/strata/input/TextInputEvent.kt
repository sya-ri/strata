package dev.s7a.strata.input

import java.util.Collections

/**
 * Immutable committed-character or input-method preedit event delivered to the currently focused component.
 */
public sealed interface TextInputEvent {
    /**
     * One committed Unicode scalar value.
     *
     * @property codePoint valid Unicode scalar value supplied by the platform.
     */
    public data class Character(
        public val codePoint: Int,
    ) : TextInputEvent {
        init {
            require(0 <= codePoint && codePoint <= 0x10FFFF) { "Text input requires a valid Unicode code point." }
            require((codePoint in 0xD800..0xDFFF).not()) { "Text input cannot contain an isolated surrogate." }
        }

        /**
         * Returns this scalar value as one UTF-16 string.
         *
         * @return one or two UTF-16 code units representing [codePoint].
         */
        public fun asString(): String =
            if (codePoint <= 0xFFFF) {
                codePoint.toChar().toString()
            } else {
                val supplementary = codePoint - 0x10000
                charArrayOf(
                    ((supplementary ushr 10) + 0xD800).toChar(),
                    ((supplementary and 0x3FF) + 0xDC00).toChar(),
                ).concatToString()
            }
    }

    /**
     * Immutable input-method composition state.
     *
     * The constructor snapshots [blocks] and exposes an unmodifiable list.
     * Empty text clears active composition.
     *
     * @param fullText complete current preedit text.
     * @param caretPosition UTF-16 caret offset within [fullText].
     * @param blocks ordered platform composition blocks.
     * @param focusedBlock selected block index, or -1 when no block is selected.
     * @throws IllegalArgumentException when caret or block indices are outside their typed ranges.
     */
    public class Preedit(
        public val fullText: String,
        public val caretPosition: Int,
        blocks: List<String>,
        public val focusedBlock: Int,
    ) : TextInputEvent {
        /**
         * Detached immutable composition blocks.
         */
        public val blocks: List<String> = Collections.unmodifiableList(blocks.toList())

        init {
            require(caretPosition in 0..fullText.length) { "Preedit caret is outside the composition text." }
            require(focusedBlock == -1 || focusedBlock in this.blocks.indices) { "Preedit focused block is outside the block list." }
        }

        override fun equals(other: Any?): Boolean =
            other is Preedit &&
                fullText == other.fullText &&
                caretPosition == other.caretPosition &&
                blocks == other.blocks &&
                focusedBlock == other.focusedBlock

        override fun hashCode(): Int {
            var result = fullText.hashCode()
            result = 31 * result + caretPosition
            result = 31 * result + blocks.hashCode()
            return 31 * result + focusedBlock
        }

        override fun toString(): String = "Preedit(fullText=$fullText, caretPosition=$caretPosition, blocks=$blocks, focusedBlock=$focusedBlock)"
    }
}
