package dev.s7a.strata.runtime.minecraft

/**
 * Typed preferred opportunities for Strata's whitespace-based word wrapping.
 *
 * This is not a dictionary or a complete Unicode line-breaking engine.
 * Non-breaking spaces never create a preferred break, although an overlong word can still use the documented scalar fallback.
 */
internal enum class MinecraftTextBreakOpportunity {
    /**
     * No preferred break follows this scalar.
     */
    None,

    /**
     * Breakable whitespace belongs to the preceding line when it fits.
     */
    Whitespace,
    ;

    /**
     * Decodes a Unicode scalar into the supported preferred-break policy.
     */
    companion object {
        /**
         * Classifies whitespace while excluding NBSP, figure space, and narrow NBSP.
         *
         * @param codePoint validated Unicode scalar from a line without mandatory breaks.
         * @return preferred whitespace opportunity or scalar-fallback-only behavior.
         */
        @JvmSynthetic
        internal fun of(codePoint: Int): MinecraftTextBreakOpportunity =
            when (codePoint) {
                0xA0, 0x2007, 0x202F -> None
                else -> if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) Whitespace else None
            }
    }
}
