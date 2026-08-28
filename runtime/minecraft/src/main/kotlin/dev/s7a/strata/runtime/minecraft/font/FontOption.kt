package dev.s7a.strata.runtime.minecraft.font

/**
 * Typed options decoded from native provider-filter keys at the resource boundary.
 *
 * @property serializedName external provider-filter key accepted by the native resource format.
 */
internal enum class FontOption(
    val serializedName: String,
) {
    /**
     * Selects the forced-Unicode provider variant.
     */
    Uniform("uniform"),

    /**
     * Selects Japanese glyph variants.
     */
    JapaneseVariants("jp"),
    ;

    /**
     * Converts external provider keys into typed immutable options.
     */
    companion object {
        /**
         * Resolves a native filter key without retaining parser state.
         *
         * @param name external JSON key.
         * @return the matching typed option.
         * @throws IllegalArgumentException for unsupported option keys.
         */
        fun decode(name: String): FontOption = requireNotNull(entries.firstOrNull { option -> option.serializedName == name }) { "Unsupported font option: $name." }
    }
}
