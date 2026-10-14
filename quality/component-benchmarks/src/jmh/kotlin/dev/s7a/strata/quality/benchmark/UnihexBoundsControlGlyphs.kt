package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph

/**
 * Independent glyph-output checks for the unchanged bitmap and TrueType consumer controls.
 * Complete native parity remains separate acceptance evidence.
 */
internal object UnihexBoundsControlGlyphs {
    /**
     * Checks every straight-ARGB bitmap texel against the frozen checkerboard source.
     */
    internal fun bitmap(glyph: MinecraftFontGlyph) {
        val image = checkNotNull(glyph.image)
        check(image.size == IntSize(8, 8))
        val expected = IntArray(64) { index -> if ((index % 8 + index / 8) % 2 == 0) -1 else 0x00ffffff }
        check(image.copyArgb().contentEquals(expected))
    }

    /**
     * Checks uncached native TrueType raster and metric repeat stability alongside independent native parity.
     */
    internal fun trueType(
        first: Any,
        second: Any,
    ) {
        check(first is MinecraftFontGlyph && second is MinecraftFontGlyph && first.image !== second.image)
        check(checkNotNull(first.image).copyArgb().contentEquals(checkNotNull(second.image).copyArgb()))
        check(first.copy(image = null) == second.copy(image = null))
    }
}
