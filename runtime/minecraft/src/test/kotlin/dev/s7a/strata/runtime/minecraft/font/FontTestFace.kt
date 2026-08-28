package dev.s7a.strata.runtime.minecraft.font

/**
 * Test-thread native face stand-in with explicit glyph and release observations.
 * It retains only the supplied callbacks and never borrows resource bytes or creates native state.
 *
 * @param lookup synchronous scalar lookup used by the owning test.
 * @param release synchronous terminal observation, including deliberately injected failures.
 */
internal class FontTestFace(
    private val lookup: (Int) -> MinecraftFontGlyph?,
    private val release: () -> Unit = {},
) : MinecraftTrueTypeFace {
    override fun glyph(codePoint: Int): MinecraftFontGlyph? = lookup(codePoint)

    override fun close() = release()
}
