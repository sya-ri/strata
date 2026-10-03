package dev.s7a.strata.quality.benchmark

/**
 * Fixed independent font-provider inputs, including disabled caches and native face capacity pressure.
 */
public enum class FontWorkload {
    BitmapCached,
    BitmapUncached,
    UnihexCached,
    UnihexUncached,
    ReferenceDepth128,
    ReferenceDepth129,
    StbCached,
    FreeTypeCached,
    StbFaces1,
    FreeTypeFaces16,
}
