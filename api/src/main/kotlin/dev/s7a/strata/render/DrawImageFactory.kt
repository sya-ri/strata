@file:JvmName("DrawImages")

package dev.s7a.strata.render

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.internal.toIntExact
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.jvm.JvmName
import kotlin.jvm.JvmSynthetic

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
    val area = (size.width.toLong() * size.height).toIntExact()
    require(area == argb.size) { "Pixel array length must equal the image area." }
    return DrawImageSnapshot(size, argb.copyOf())
}

/**
 * Transfers exclusively owned runtime pixels into an immutable image without copying their backing array.
 *
 * The caller must relinquish every mutable alias after a successful call and must never modify [argb] afterward.
 * This privileged bridge is for fresh private runtime buffers; application arrays use [createDrawImage] instead.
 * Validation completes before ownership transfers. The result retains the ordinary immutable image equality and copy contract.
 *
 * @param size the non-negative extent whose checked area must fit in `Int`.
 * @param argb an exclusively owned row-major straight-ARGB buffer matching the checked image area.
 * @return an immutable, thread-safe image owning the transferred buffer.
 * @throws ArithmeticException when the checked image area overflows `Int`.
 * @throws IllegalArgumentException when the buffer length does not equal the checked image area.
 */
@InternalStrataRuntimeApi
@JvmSynthetic
public fun createOwnedDrawImage(
    size: IntSize,
    argb: IntArray,
): DrawImage {
    val area = (size.width.toLong() * size.height).toIntExact()
    require(area == argb.size) { "Pixel array length must equal the image area." }
    return DrawImageSnapshot(size, argb)
}

/**
 * Generates an immutable image directly into its privately owned storage.
 *
 * [pixelAt] is borrowed synchronously on the caller's thread, once per pixel in row-major order, and never retained.
 * Empty images do not invoke it. A callback failure propagates without publishing a partial image.
 * The returned value has the same equality and copy contract as the array overload.
 *
 * @param size the non-negative source extent whose checked area must fit in `Int`.
 * @param pixelAt supplies straight, non-premultiplied `0xAARRGGBB` pixels at the given x and y coordinates.
 * @return an immutable, thread-safe image owning the generated pixels.
 * @throws ArithmeticException when the checked image area overflows `Int`, before invoking [pixelAt].
 */
public fun createDrawImage(
    size: IntSize,
    pixelAt: (x: Int, y: Int) -> Int,
): DrawImage {
    val area = (size.width.toLong() * size.height).toIntExact()
    val pixels = IntArray(area)
    if (area == 0) return DrawImageSnapshot(size, pixels)
    for (y in 0 until size.height) {
        for (x in 0 until size.width) pixels[y * size.width + x] = pixelAt(x, y)
    }
    return DrawImageSnapshot(size, pixels)
}

private class DrawImageSnapshot(
    override val size: IntSize,
    private val pixels: IntArray,
) : DrawImage {
    override fun argbAt(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until size.width) { "X coordinate must be inside the image." }
        require(y in 0 until size.height) { "Y coordinate must be inside the image." }
        return pixels[y * size.width + x]
    }

    override fun copyArgb(): IntArray = pixels.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DrawImageSnapshot && size == other.size && pixels.contentEquals(other.pixels))

    override fun hashCode(): Int = 31 * size.hashCode() + pixels.contentHashCode()
}
