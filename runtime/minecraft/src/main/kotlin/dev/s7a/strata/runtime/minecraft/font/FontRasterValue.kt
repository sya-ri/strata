package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.render.DrawImage

internal sealed interface FontRasterValue {
    fun bytes(): Long

    data class Glyph(
        val value: MinecraftFontGlyph?,
    ) : FontRasterValue {
        override fun bytes(): Long = value?.image?.let { image -> image.size.width.toLong() * image.size.height * 4L } ?: 0L
    }

    data class GlyphFailure(
        val message: String,
    ) : FontRasterValue {
        override fun bytes(): Long = 0L
    }

    data class Bitmap(
        val value: DrawImage,
    ) : FontRasterValue {
        override fun bytes(): Long = value.size.width.toLong() * value.size.height * 4L
    }

    data class BitmapCell(
        val pixels: FontBitmapCell,
        val height: Int,
        val ascent: Int,
        val glyph: MinecraftFontGlyph,
    ) : FontRasterValue {
        override fun bytes(): Long = glyph.image?.let { image -> image.size.width.toLong() * image.size.height * 4L } ?: 0L
    }
}
