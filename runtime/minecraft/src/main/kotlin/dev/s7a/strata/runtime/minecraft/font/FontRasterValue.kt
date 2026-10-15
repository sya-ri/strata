package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.render.DrawImage

/**
 * Detached raster results and failures with the original ARGB payload accounting.
 */
internal sealed interface FontRasterValue {
    /**
     * Reports retained pixel bytes, excluding cache keys and object headers.
     */
    fun bytes(): Long

    /**
     * One resolved glyph or cached miss, weighted only by its returned image.
     */
    data class Glyph(
        val value: MinecraftFontGlyph?,
    ) : FontRasterValue {
        override fun bytes(): Long = value?.image?.let { image -> image.size.width.toLong() * image.size.height * 4L } ?: 0L
    }

    /**
     * Detached ordinary failure text that owns no pixel payload.
     */
    data class GlyphFailure(
        val message: String,
    ) : FontRasterValue {
        override fun bytes(): Long = 0L
    }

    /**
     * A decoded source image weighted by its complete ARGB dimensions.
     */
    data class Bitmap(
        val value: DrawImage,
    ) : FontRasterValue {
        override fun bytes(): Long = value.size.width.toLong() * value.size.height * 4L
    }

    /**
     * Original sampled cell pixels, dimensions and detached returned glyph.
     */
    data class BitmapCell(
        val pixels: FontBitmapCell,
        val height: Int,
        val ascent: Int,
        val glyph: MinecraftFontGlyph,
    ) : FontRasterValue {
        override fun bytes(): Long = glyph.image?.let { image -> image.size.width.toLong() * image.size.height * 4L } ?: 0L
    }
}
