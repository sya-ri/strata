package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntSize

/**
 * An immutable, thread-safe source image for platform-neutral draw commands.
 *
 * Pixels are row-major straight, non-premultiplied `0xAARRGGBB` values.
 * Implementations own their storage and expose neither a mutable array nor a public construction surface.
 */
public sealed interface DrawImage {
    /**
     * The image extent in source pixel coordinates.
     */
    public val size: IntSize

    /**
     * Reads one source pixel without changing the image.
     *
     * @param x the source pixel x coordinate.
     * @param y the source pixel y coordinate.
     * @return the straight, non-premultiplied `0xAARRGGBB` pixel value.
     * @throws IllegalArgumentException when either coordinate is outside [size].
     */
    public fun argbAt(
        x: Int,
        y: Int,
    ): Int

    /**
     * Returns a fresh row-major copy of every source pixel.
     *
     * @return an independent array with `size.width` pixels per row.
     */
    public fun copyArgb(): IntArray
}
