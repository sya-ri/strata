package dev.s7a.strata.text

/**
 * Typed fallback policy for an unresolved translation.
 */
public sealed interface TranslationFallback {
    /**
     * Preserves and displays the translation key when lookup is unavailable.
     */
    public data object UseKey : TranslationFallback

    /**
     * Supplies a literal fallback retained independently of translation lookup.
     *
     * @property value the literal fallback string.
     */
    public data class Literal(
        public val value: String,
    ) : TranslationFallback
}
