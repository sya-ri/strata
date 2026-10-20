package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage

/**
 * Resamples an arbitrary-size head region with exact premultiplied bilinear weights.
 * The result belongs to the current description; no historical skin or image cache is retained.
 */
internal fun sampleWebHeadRegion(
    image: DrawImage,
    source: IntRect,
    size: Int,
): DrawImage {
    val denominator = size.toLong() * 2
    val lower = IntArray(size)
    val upper = IntArray(size)
    val fraction = LongArray(size)
    for (index in 0 until size) {
        val numerator = (index.toLong() * 2 + 1) * 8 - size
        if (0 < numerator) {
            if (7 * denominator <= numerator) {
                lower[index] = 7
                upper[index] = 7
            } else {
                lower[index] = (numerator / denominator).toInt()
                upper[index] = lower[index] + 1
                fraction[index] = numerator % denominator
            }
        }
    }
    val samples = IntArray(4)
    val weights = LongArray(4)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            samples[0] = image.argbAt(source.left + lower[x], source.top + lower[y])
            samples[1] = image.argbAt(source.left + upper[x], source.top + lower[y])
            samples[2] = image.argbAt(source.left + lower[x], source.top + upper[y])
            samples[3] = image.argbAt(source.left + upper[x], source.top + upper[y])
            weights[0] = (denominator - fraction[x]) * (denominator - fraction[y])
            weights[1] = fraction[x] * (denominator - fraction[y])
            weights[2] = (denominator - fraction[x]) * fraction[y]
            weights[3] = fraction[x] * fraction[y]
            pixels[y * size + x] = interpolateWebHead(samples, weights, denominator * denominator)
        }
    }
    return createDrawImage(IntSize(size, size), pixels)
}

private fun interpolateWebHead(
    samples: IntArray,
    weights: LongArray,
    total: Long,
): Int {
    var alpha = 0L
    for (index in samples.indices) alpha += (samples[index] ushr 24) * weights[index]
    val roundedAlpha = ((alpha + total / 2) / total).toInt().coerceIn(0, 255)
    if (roundedAlpha == 0) return 0
    var result = roundedAlpha shl 24
    for (shift in listOf(16, 8, 0)) {
        var channel = 0L
        for (index in samples.indices) channel += (samples[index] ushr shift and 255) * (samples[index] ushr 24) * weights[index]
        result = result or (((channel + alpha / 2) / alpha).toInt().coerceIn(0, 255) shl shift)
    }
    return result
}
