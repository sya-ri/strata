@file:JvmName("DrawImages")

package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntSize

/**
 * Creates an immutable source image for platform-neutral drawing.
 *
 * The input array is copied before the image is returned, and every [DrawImage.copyArgb] call returns a fresh copy.
 * The checked area is exactly `size.width * size.height`; zero-width or zero-height extents therefore require an empty array.
 * Equal sizes and pixel values produce equal images with equal hash codes.
 *
 * @param size the non-negative source image extent.
 * @param argb row-major straight, non-premultiplied `0xAARRGGBB` pixels.
 * @return an immutable, thread-safe image value.
 * @throws ArithmeticException when the checked image area overflows `Int`.
 * @throws IllegalArgumentException when the pixel array length does not equal the checked image area.
 */
public fun createDrawImage(
    size: IntSize,
    argb: IntArray,
): DrawImage {
    val area = Math.multiplyExact(size.width, size.height)
    require(area == argb.size) { "Pixel array length must equal the image area." }
    return DrawImageSnapshot.create(size, argb.copyOf())
}

private class DrawImageSnapshot private constructor(
    override val size: IntSize,
    private val pixels: IntArray,
) : DrawImage {
    override fun argbAt(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until size.width) { "X coordinate must be inside the image." }
        require(y in 0 until size.height) { "Y coordinate must be inside the image." }
        val rowOffset = Math.multiplyExact(y, size.width)
        return pixels[Math.addExact(rowOffset, x)]
    }

    override fun copyArgb(): IntArray = pixels.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DrawImageSnapshot && size == other.size && pixels.contentEquals(other.pixels))

    override fun hashCode(): Int = 31 * size.hashCode() + pixels.contentHashCode()

    companion object {
        /**
         * Creates one private snapshot after the caller has validated the image area.
         *
         * @param size the immutable image extent.
         * @param pixels the already detached row-major pixel storage.
         * @return a private immutable image implementation.
         */
        @JvmSynthetic
        internal fun create(
            size: IntSize,
            pixels: IntArray,
        ): DrawImageSnapshot = DrawImageSnapshot(size, pixels)
    }
}
