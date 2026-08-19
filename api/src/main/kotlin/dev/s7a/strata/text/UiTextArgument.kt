package dev.s7a.strata.text

/**
 * A type-safe argument retained inside translated text.
 */
public sealed interface UiTextArgument {
    /**
     * A translated or literal nested text argument.
     */
    public data class Text(
        public val value: UiText,
    ) : UiTextArgument

    /**
     * A string argument.
     */
    public data class StringValue(
        public val value: String,
    ) : UiTextArgument

    /**
     * An integer argument.
     */
    public data class IntValue(
        public val value: Int,
    ) : UiTextArgument

    /**
     * A long argument.
     */
    public data class LongValue(
        public val value: Long,
    ) : UiTextArgument

    /**
     * A floating-point argument.
     *
     * @property value the argument value.
     */
    public data class FloatValue(
        public val value: Float,
    ) : UiTextArgument

    /**
     * A double-precision floating-point argument.
     *
     * @property value the argument value.
     */
    public data class DoubleValue(
        public val value: Double,
    ) : UiTextArgument

    /**
     * A boolean argument.
     */
    public data class BooleanValue(
        public val value: Boolean,
    ) : UiTextArgument
}
