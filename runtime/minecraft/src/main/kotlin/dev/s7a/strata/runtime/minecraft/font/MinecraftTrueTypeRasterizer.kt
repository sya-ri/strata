package dev.s7a.strata.runtime.minecraft.font

/**
 * Selects the native CPU rasterization contract supplied by a supported release adapter.
 */
public enum class MinecraftTrueTypeRasterizer {
    /**
     * The stb_truetype provider contract used by the earlier supported releases.
     */
    Stb,

    /**
     * The FreeType provider contract used by the later supported releases.
     */
    FreeType,
}
