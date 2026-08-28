package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftGlyphChannel

/**
 * Detached provider metrics captured before allocating source pixels.
 * Finite reversed axes are normalized with their sampling direction; non-finite native coordinates remain unchanged.
 * The owner-thread caller supplies pixels only when the measured raster fits the native 256-pixel atlas in both axes.
 *
 * @param advance raw native cursor advance, including IEEE results from zero oversampling.
 * @param left native first horizontal ink coordinate.
 * @param top native first vertical ink coordinate.
 * @param right native second horizontal ink coordinate.
 * @param bottom native second vertical ink coordinate.
 * @param rasterSize positive measured source dimensions, independent of logical oversampling.
 */
internal class TrueTypeGlyphMetrics(
    private val advance: Float,
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val rasterSize: IntSize,
) {
    /**
     * Returns detached glyph data without allocating an oversized raster.
     * The callback executes once for an atlas-sized raster and never for an oversized one; its failures propagate unchanged.
     * The engine owns version-specific missing-sprite substitution, so raw provider metrics remain intact here.
     *
     * @param pixels owner-thread callback producing the complete immutable measured raster.
     * @return detached native metrics with finite axes normalized, and either pixels or an explicit oversized-raster marker.
     */
    fun rasterize(pixels: () -> DrawImage): MinecraftFontGlyph {
        val oversized = rasterSize.takeIf { size -> 256 < size.width || 256 < size.height }
        val flipX = left.isFinite() && right.isFinite() && right < left
        val flipY = top.isFinite() && bottom.isFinite() && bottom < top
        return MinecraftFontGlyph(
            advance,
            if (flipX) right else left,
            if (flipY) bottom else top,
            if (flipX) left else right,
            if (flipY) top else bottom,
            if (oversized == null) pixels() else null,
            MinecraftGlyphChannel.Intensity,
            orientation = samplingOrientation(flipX, flipY),
            oversizedRasterSize = oversized,
        )
    }

    private fun samplingOrientation(
        flipX: Boolean,
        flipY: Boolean,
    ): SampledImageOrientation =
        when {
            flipX && flipY -> SampledImageOrientation.FlipBoth
            flipX -> SampledImageOrientation.FlipHorizontal
            flipY -> SampledImageOrientation.FlipVertical
            else -> SampledImageOrientation.Normal
        }
}
