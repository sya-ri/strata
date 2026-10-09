package dev.s7a.strata.quality.benchmark

/**
 * Stable independent fallback-selection inputs, including unchanged-work and failure controls.
 */
public enum class FontFallbackWorkload {
    /**
     * First-provider hit; extra resolution work must remain visible.
     */
    First,

    /**
     * Space glyph after cached scalar misses in every preceding provider.
     */
    Late,

    /**
     * Complete-chain miss with the runtime's native missing metrics and pixels.
     */
    Missing,

    /**
     * Applicable late provider after captured uniform-option filters reject preceding providers.
     */
    FilteredLate,

    /**
     * Real STB glyph after cached misses; raster and face lifetimes remain unchanged.
     */
    StbLate,

    /**
     * Real FreeType glyph after cached misses; raster and face lifetimes remain unchanged.
     */
    FreeTypeLate,

    /**
     * Oversized bitmap result selects its provider and retains the effective missing shape.
     */
    AtlasRejected,

    /**
     * Disabled malformed native provider still poisons the entire preflighted bundle.
     */
    Poisoned,
}
