package dev.s7a.strata.runtime.minecraft.font

import kotlin.math.ceil

/**
 * Immutable resource-format and rasterization capabilities selected by the caller's release adapter.
 * No game version is inferred from names or runtime strings.
 *
 * @property rasterizer exact TrueType rasterizer family supplied by the backend.
 * @property packFormat resource-pack format used to select declared overlays.
 * @property providerFilters whether font provider filters are interpreted.
 * @property packOverlays whether resource-pack overlays are interpreted.
 * @property packFormatMinor minor pack-format revision used by minor-aware overlays.
 * @property minorPackFormats whether overlay min_format and max_format fields are supported.
 * @property interleavedShadows whether each glyph's shadow is drawn immediately before its foreground.
 * @property fractionalUnihexAdvance whether Unihex advance divides its cropped width as a float instead of truncating integer division.
 * @property rejectMalformedOverlayMetadata whether malformed overlay metadata excludes its entire selected source instead of ignoring the overlay section.
 * @property bakedGlyphMetrics whether an atlas-rejected glyph uses the missing glyph's advance as well as its pixels.
 * @property saturatingCeil whether text width rounds in double precision before the saturating integer conversion.
 * @property preparedTextBounds whether drawing applies the native whole-line raw ink bounds rejection before emitting glyphs.
 */
public data class MinecraftFontCompatibility(
    public val rasterizer: MinecraftTrueTypeRasterizer,
    public val packFormat: Int,
    public val providerFilters: Boolean = true,
    public val packOverlays: Boolean = true,
    public val packFormatMinor: Int = 0,
    public val minorPackFormats: Boolean = true,
    public val interleavedShadows: Boolean = true,
    public val fractionalUnihexAdvance: Boolean = false,
    public val rejectMalformedOverlayMetadata: Boolean = false,
    public val bakedGlyphMetrics: Boolean = false,
    public val saturatingCeil: Boolean = false,
    public val preparedTextBounds: Boolean = false,
) {
    /**
     * Creates the earlier capability contract without whole-line prepared bounds rejection.
     * Inputs and immutable ownership follow the primary constructor.
     */
    public constructor(
        rasterizer: MinecraftTrueTypeRasterizer,
        packFormat: Int,
        providerFilters: Boolean = true,
        packOverlays: Boolean = true,
        packFormatMinor: Int = 0,
        minorPackFormats: Boolean = true,
        interleavedShadows: Boolean = true,
        fractionalUnihexAdvance: Boolean = false,
        rejectMalformedOverlayMetadata: Boolean = false,
        bakedGlyphMetrics: Boolean = false,
        saturatingCeil: Boolean = false,
    ) : this(
        rasterizer,
        packFormat,
        providerFilters,
        packOverlays,
        packFormatMinor,
        minorPackFormats,
        interleavedShadows,
        fractionalUnihexAdvance,
        rejectMalformedOverlayMetadata,
        bakedGlyphMetrics,
        saturatingCeil,
        false,
    )

    /**
     * Creates the earlier capability contract without baked-metric replacement or saturating width rounding.
     * Inputs and immutable ownership follow the primary constructor.
     */
    public constructor(
        rasterizer: MinecraftTrueTypeRasterizer,
        packFormat: Int,
        providerFilters: Boolean = true,
        packOverlays: Boolean = true,
        packFormatMinor: Int = 0,
        minorPackFormats: Boolean = true,
        interleavedShadows: Boolean = true,
        fractionalUnihexAdvance: Boolean = false,
        rejectMalformedOverlayMetadata: Boolean = false,
    ) : this(
        rasterizer,
        packFormat,
        providerFilters,
        packOverlays,
        packFormatMinor,
        minorPackFormats,
        interleavedShadows,
        fractionalUnihexAdvance,
        rejectMalformedOverlayMetadata,
        false,
        false,
        false,
    )

    init {
        require(0 <= packFormat) { "Resource-pack format must be non-negative." }
        require(0 <= packFormatMinor) { "Resource-pack minor format must be non-negative." }
    }

    /**
     * Converts an accumulated native advance to the selected release's signed text width without clamping it to layout geometry.
     * The pure operation accepts every floating-point value and retains native overflow behavior.
     * NaN becomes zero and negative infinity becomes [Int.MIN_VALUE] in both modes.
     * Positive infinity becomes [Int.MAX_VALUE] with [saturatingCeil], or [Int.MIN_VALUE] through the earlier increment overflow.
     *
     * @param advance logical glyph advances accumulated with native floating-point arithmetic.
     * @return the native signed width; callers must project negative results before constructing non-negative layout extents.
     */
    public fun roundedWidth(advance: Float): Int {
        if (saturatingCeil) return ceil(advance.toDouble()).toInt()
        val truncated = advance.toInt()
        return if (truncated.toFloat() < advance) truncated + 1 else truncated
    }
}
