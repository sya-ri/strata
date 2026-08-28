package dev.s7a.strata.runtime.minecraft.font

/**
 * Owner-thread native face borrowed only by one font engine.
 * Every returned glyph is detached from the face; close releases all native handles and is idempotent.
 */
public interface MinecraftTrueTypeFace : AutoCloseable {
    /**
     * Rasterizes one Unicode scalar, returning null when the font has no glyph.
     *
     * @param codePoint valid Unicode scalar value.
     * @return detached metrics and pixels, or null for a missing glyph.
     * @throws Throwable when native loading or rasterization fails.
     */
    public fun glyph(codePoint: Int): MinecraftFontGlyph?

    /**
     * Releases this face on its owner thread without invalidating returned glyphs.
     */
    override fun close()
}
