package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable font-provider selections and fallback text direction captured with one resource snapshot.
 *
 * @property uniform whether the forced-Unicode font option is selected.
 * @property japaneseVariants whether the Japanese glyph-variant option is selected.
 * @property rightToLeft whether the selected language supplies a right-to-left fallback paragraph direction.
 */
public data class MinecraftFontOptions(
    public val uniform: Boolean = false,
    public val japaneseVariants: Boolean = false,
    public val rightToLeft: Boolean = false,
)
