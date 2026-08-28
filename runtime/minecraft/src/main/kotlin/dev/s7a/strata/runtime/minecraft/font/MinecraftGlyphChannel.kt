package dev.s7a.strata.runtime.minecraft.font

/**
 * Describes the native text shader's interpretation of detached source texels.
 */
public enum class MinecraftGlyphChannel {
    /**
     * Source RGBA is multiplied by the requested text color.
     */
    Color,

    /**
     * Glyph coverage has already been replicated into every channel before color multiplication.
     */
    Intensity,
}
