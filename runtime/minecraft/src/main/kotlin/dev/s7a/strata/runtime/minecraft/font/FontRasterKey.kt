package dev.s7a.strata.runtime.minecraft.font

internal sealed interface FontRasterKey {
    data class Glyph(
        val provider: Int,
        val codePoint: Int,
    ) : FontRasterKey

    data class Bitmap(
        val resource: FontResource,
    ) : FontRasterKey

    data class BitmapCell(
        val resource: FontResource,
        val columns: Int,
        val rows: Int,
        val cell: Int,
    ) : FontRasterKey

    data class TrueTypeGlyph(
        val face: FontFaceKey,
        val codePoint: Int,
    ) : FontRasterKey
}
