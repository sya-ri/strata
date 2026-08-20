package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntSize

/**
 * An immutable, thread-safe row-major physical ARGB image produced by headless rendering.
 *
 * The implementation owns its pixel storage and exposes no mutable backing array or construction hook.
 * Image reads and fresh output copies are safe from any thread after construction.
 * Coordinates are physical pixels with a top-left origin.
 *
 * @property size the positive physical image extent.
 */
public sealed interface HeadlessImage {
    /**
     * The positive physical image extent.
     */
    public val size: IntSize

    /**
     * Returns one physical pixel without changing the image.
     *
     * @param x the physical x coordinate.
     * @param y the physical y coordinate.
     * @return the straight, non-premultiplied ARGB value whose alpha occupies bits 24 through 31, red bits 16 through 23, green bits 8 through 15, and blue bits 0 through 7, matching the ARGB value contract.
     * @throws IllegalArgumentException when a coordinate is outside the image.
     */
    public fun argbAt(
        x: Int,
        y: Int,
    ): Int

    /**
     * Returns a fresh row-major copy of all physical ARGB pixels.
     *
     * @return an independent array ordered left-to-right within each top-to-bottom row.
     */
    public fun copyArgb(): IntArray

    /**
     * Encodes the image as deterministic PNG bytes.
     *
     * The result uses exactly one IHDR, one IDAT, and one IEND chunk in that order, RGBA8 noninterlaced scanlines with filter zero, deterministic stored DEFLATE blocks of at most 65,535 bytes, and CRC32 and Adler32 checksums.
     * No metadata, timestamps, gamma conversion, or platform encoder state is included.
     * Equal image sizes and pixels encode byte-for-byte identically on every supported JVM and operating system, and every returned array is fresh and independent.
     *
     * @return a fresh byte array containing the PNG.
     * @throws ArithmeticException when checked PNG size arithmetic cannot be represented.
     */
    public fun encodePng(): ByteArray
}
