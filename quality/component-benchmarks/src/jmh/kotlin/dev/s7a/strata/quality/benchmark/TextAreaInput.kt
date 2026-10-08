package dev.s7a.strata.quality.benchmark

/**
 * Frozen normalized UTF-16 sizes and immutable input distributions for public state admission.
 * Text preparation and discarded-source derivation occur outside every measured operation.
 */
public enum class TextAreaInput(
    private val size: Int,
    private val piece: String? = null,
    private val separator: String? = null,
    private val position: Int = 0,
) {
    Empty(0),
    Short(8),
    Ascii2048(2_048),
    Ascii16384(16_384),
    Maximum32767(32_767),
    Lf2048(2_048, "A\n"),
    Lf16384(16_384, "A\n"),
    Bmp2048(2_048, "日本語한글"),
    Bmp16384(16_384, "日本語한글"),
    Supplementary2048(2_048, "🙂"),
    Supplementary16384(16_384, "🙂"),
    CombiningDirection2048(2_048, "e\u0301\u2066A\u2069\u200D"),
    CombiningDirection16384(16_384, "e\u0301\u2066A\u2069\u200D"),
    CrLfShort(8, separator = "\r\n"),
    CrShort(8, separator = "\r"),
    VtShort(8, separator = "\u000B"),
    FfShort(8, separator = "\u000C"),
    NelShort(8, separator = "\u0085"),
    LineShort(8, separator = "\u2028"),
    ParagraphShort(8, separator = "\u2029"),
    CrLfFirst2048(2_048, separator = "\r\n"),
    CrFirst2048(2_048, separator = "\r"),
    VtFirst2048(2_048, separator = "\u000B"),
    FfFirst2048(2_048, separator = "\u000C"),
    NelFirst2048(2_048, separator = "\u0085"),
    LineFirst2048(2_048, separator = "\u2028"),
    ParagraphFirst2048(2_048, separator = "\u2029"),
    CrLfEarly2048(2_048, separator = "\r\n", position = 1),
    CrLfMiddle2048(2_048, separator = "\r\n", position = 1_024),
    CrLfLast2048(2_048, separator = "\r\n", position = 2_047),
    CrLfFirst16384(16_384, separator = "\r\n"),
    CrLfEarly16384(16_384, separator = "\r\n", position = 1),
    CrLfMiddle16384(16_384, separator = "\r\n", position = 8_192),
    CrLfLast16384(16_384, separator = "\r\n", position = 16_383),
    LineMiddle16384(16_384, separator = "\u2028", position = 8_192),
    LineLast16384(16_384, separator = "\u2028", position = 16_383),
    DenseCrLf2048(2_048, "A\n", "\r\n"),
    DenseCrLf16384(16_384, "A\n", "\r\n"),
    ContractedCrLf16384(16_384, "\n", "\r\n"),
    MixedCr16384(16_384, "A\n\n", "\r"),
    DiscardedSubstring2048(2_048),
    DiscardedConcatenation16384(16_384),
    ;

    /**
     * Independent raw and canonical variants; the canonical expectations are constructed before separator substitution.
     */
    public fun prepare(): Values {
        val fragment = piece ?: "A"
        val canonical =
            if (separator != null && piece == null) {
                "A".repeat(position) + "\n" + "A".repeat(size - position - 1)
            } else {
                fragment.repeat(size / fragment.length) + "A".repeat(size % fragment.length)
            }
        val first =
            when (this) {
                DiscardedSubstring2048 -> ("X".repeat(1_048_576) + canonical).substring(1_048_576)
                DiscardedConcatenation16384 -> ("X".repeat(1_048_576) + canonical).substring(1_048_576, 1_048_576 + size / 2) + canonical.substring(size / 2)
                else -> canonical
            }
        val change = first.indexOf('A')
        val second =
            when {
                0 <= change -> first.replaceRange(change, change + 1, "B")
                first.isEmpty() -> first
                this == Supplementary2048 || this == Supplementary16384 -> "🙃" + first.substring(2)
                else -> "B" + first.substring(1)
            }
        val expected = listOf(first, second)
        val raw = expected.map { text ->
            when {
                this == MixedCr16384 -> text.replace("\n\n", "\r\r\n")
                separator == null -> text
                else -> text.replace("\n", separator)
            }
        }
        return Values(raw, expected, maxOf(1, size))
    }

    /**
     * Prepared caller-owned immutable input and independent expected output; no validator supplies expectations.
     */
    public data class Values(
        public val raw: List<String>,
        public val canonical: List<String>,
        public val maximum: Int,
    )
}
