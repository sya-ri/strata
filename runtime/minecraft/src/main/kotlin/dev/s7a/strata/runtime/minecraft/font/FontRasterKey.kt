package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable identities retained only by one engine's bounded raster cache.
 */
internal sealed interface FontRasterKey {
    /**
     * One scalar lookup under the exact snapshot-local provider declaration.
     */
    data class Glyph(
        val provider: Int,
        val codePoint: Int,
    ) : FontRasterKey

    /**
     * The complete decoded image for one captured resource identity.
     */
    data class Bitmap(
        val resource: FontResource,
    ) : FontRasterKey

    /**
     * One cell under the captured resource, grid dimensions and cell position.
     */
    data class BitmapCell(
        val resource: FontResource,
        val columns: Int,
        val rows: Int,
        val cell: Int,
    ) : FontRasterKey

    /**
     * One scalar raster under the exact native-face resource and settings.
     */
    data class TrueTypeGlyph(
        val face: FontFaceKey,
        val codePoint: Int,
    ) : FontRasterKey
}
