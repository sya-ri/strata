package dev.s7a.strata.text

/**
 * Immutable line layout requested by a displayed text component.
 *
 * Runtime measurement resolves the policy against its font metrics and the available constraints.
 * The policy owns no text or platform resources and may be shared across threads.
 */
public sealed interface TextLayout {
    /**
     * Preserves the existing single-line text contract, including rejection of explicit line separators.
     */
    public data object SingleLine : TextLayout

    /**
     * Displays explicit lines with optional wrapping and a bounded number of visible lines.
     *
     * LF, CRLF, CR, VT, FF, NEL, line separator, and paragraph separator each introduce a line break; CRLF is one break.
     * Line breaking never splits a Unicode scalar and does not add selection or clipboard behavior.
     * The original unresolved text remains available for semantics even when its visible presentation is truncated.
     *
     * @property wrap presentation-only wrapping policy.
     * @property maxLines positive maximum number of displayed lines, including empty explicit lines.
     * @property overflow treatment of text omitted by the available space or [maxLines].
     * @property lineSpacing non-negative additional logical pixels between adjacent lines.
     * @throws IllegalArgumentException when [maxLines] is not positive or [lineSpacing] is negative.
     */
    public data class Multiline(
        public val wrap: TextWrap = TextWrap.Word,
        public val maxLines: Int = Int.MAX_VALUE,
        public val overflow: TextOverflow = TextOverflow.Clip,
        public val lineSpacing: Int = 0,
    ) : TextLayout {
        init {
            require(0 < maxLines) { "Text maximum line count must be positive." }
            require(0 <= lineSpacing) { "Text line spacing must be non-negative." }
        }
    }
}
