package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage

/**
 * Detached alpha-bound observation and optional pixels for one bitmap cell.
 * Atlas-rejected cells retain only dimensions and alpha metrics, never a copied cell raster or the pixel callback.
 *
 * @property rightmost last source column containing nonzero alpha, or -1 when all pixels are transparent.
 * @property size positive source dimensions retained without keeping the sheet.
 * @property image copied cell pixels when both dimensions fit the native atlas.
 * @property oversizedRasterSize source dimensions when the atlas rejects this cell before copying.
 */
internal class FontBitmapCell private constructor(
    val rightmost: Int,
    val size: IntSize,
    val image: DrawImage?,
    val oversizedRasterSize: IntSize?,
) {
    /**
     * Owns synchronous, single-pass alpha scanning without retaining the source sheet.
     */
    companion object {
        /**
         * Reads each bounded source pixel exactly once and copies only cells that fit the native 256-pixel atlas.
         * The caller must apply its sheet and dimension budgets before invoking this operation.
         *
         * @param size positive cell dimensions already checked against image input limits.
         * @param pixelAt synchronous source-pixel accessor that is not retained.
         * @return detached alpha metrics and at most one atlas-sized copied image.
         * @throws Throwable when dimensions are empty or source pixel access fails.
         */
        fun read(
            size: IntSize,
            pixelAt: (Int, Int) -> Int,
        ): FontBitmapCell {
            require(0 < size.width && 0 < size.height) { "Bitmap cells must have positive source dimensions." }
            val oversized = 256 < size.width || 256 < size.height
            val pixels = if (oversized) null else IntArray(Math.multiplyExact(size.width, size.height))
            var rightmost = -1
            for (y in 0 until size.height) {
                for (x in 0 until size.width) {
                    val pixel = pixelAt(x, y)
                    pixels?.set(y * size.width + x, pixel)
                    if (pixel ushr 24 != 0) rightmost = maxOf(rightmost, x)
                }
            }
            return FontBitmapCell(rightmost, size, pixels?.let { createDrawImage(size, it) }, size.takeIf { oversized })
        }
    }
}
