package dev.s7a.strata.quality.benchmark

/**
 * Frozen competing failures with independently specified winning messages; raw text is prepared before timing.
 */
public enum class TextAreaRejectedInput(
    private val prefix: String,
    private val repetitions: Int,
    private val tail: String,
    public val maximum: Int,
    public val failure: Failure,
) {
    ControlFirst("", 0, "\u0000", 1, Failure.Control),
    SurrogateFirst("", 0, "\uD800", 1, Failure.Surrogate),
    InvalidAtOverflow("A", 1, "\uD800", 1, Failure.Surrogate),
    ControlAtOverflow("A", 1, "\u00A7", 1, Failure.Control),
    OverflowBeforeInvalid("A", 2, "\uD800", 1, Failure.Length),
    SupplementaryOverflow("🙂", 1, "\u0000", 1, Failure.Length),
    ConvertedOverflowBeforeInvalid("\r\n", 2, "\uD800", 1, Failure.Length),
    ConvertedInvalidAtOverflow("\r\n", 1, "\uD800", 1, Failure.Surrogate),
    LongMalformedTail("A", 16_384, "\uD800", 16_384, Failure.Surrogate),
    LongOverflowBeforeTail("A", 16_384, "A\uD800", 16_384, Failure.Length),
    ContractedMalformedTail("\r\n", 16_384, "\uD800", 16_384, Failure.Surrogate),
    ContractedOverflowBeforeTail("\r\n", 16_384, "A\uD800", 16_384, Failure.Length),
    ;

    /**
     * Constructs raw input outside constructor/setter timing.
     */
    public fun prepare(): String = prefix.repeat(repetitions) + tail

    /**
     * Exact original observable failure classification, independent of candidate exception generation.
     */
    public enum class Failure(public val message: String) {
        Control("Text area value contains a control character or formatting marker."),
        Surrogate("Text area value contains an isolated surrogate."),
        Length("Text area value exceeds its maximum length after newline normalization."),
    }
}
