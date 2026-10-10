package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings

/**
 * Proposed immutable real-glyph inputs; the native admission receipt must validate them before collection.
 * Both scalars are present in the repository's original CC0 font, as separately tested by its native suite.
 *
 * @property scalar original Unicode scalar, never a glyph index.
 * @property settings exact provider input; observed native raster extents and pitch belong in the admission receipt.
 */
public enum class FreeTypeGlyphFixture(
    public val scalar: Int,
    public val settings: MinecraftTrueTypeSettings,
) {
    SmallGlyphOne(0x41, MinecraftTrueTypeSettings(11f, 2f, 0.25f, -0.5f)),
    SmallGlyphTwo(0x65e5, MinecraftTrueTypeSettings(11f, 2f, 0.25f, -0.5f)),
    LargeGlyphOne(0x41, MinecraftTrueTypeSettings(96f, 2f, 0.25f, -0.5f)),
    LargeGlyphTwo(0x65e5, MinecraftTrueTypeSettings(96f, 2f, 0.25f, -0.5f)),
}
