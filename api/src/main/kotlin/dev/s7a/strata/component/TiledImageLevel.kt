package dev.s7a.strata.component

import dev.s7a.strata.geometry.IntSize

/**
 * Describes one immutable resolution level in a tiled raster source.
 *
 * Each source pixel covers a square of [contentUnitsPerPixel] content units.
 * Multiplying that value by [tilePixelSize] gives the content extent of one tile at this level.
 * Sources list levels from finest to coarsest and keep this geometry unchanged for their entire identity lifetime.
 *
 * @property tilePixelSize exact positive pixel extent required from every ready tile at this level.
 * @property contentUnitsPerPixel positive number of content-coordinate units represented by one source pixel on both axes.
 * @throws IllegalArgumentException when a pixel extent or unit scale is not positive.
 * @throws ArithmeticException when a tile's content extent exceeds a [Long].
 */
public data class TiledImageLevel(
    public val tilePixelSize: IntSize,
    public val contentUnitsPerPixel: Long,
) {
    init {
        require(0 < tilePixelSize.width && 0 < tilePixelSize.height) { "Tiled image tile dimensions must be positive." }
        require(0 < contentUnitsPerPixel) { "Tiled image content units per pixel must be positive." }
        Math.multiplyExact(tilePixelSize.width.toLong(), contentUnitsPerPixel)
        Math.multiplyExact(tilePixelSize.height.toLong(), contentUnitsPerPixel)
    }
}
