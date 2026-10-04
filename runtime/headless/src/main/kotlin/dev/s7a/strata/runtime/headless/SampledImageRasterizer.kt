package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Samples fractional image commands directly into caller-owned physical raster storage.
 *
 * The object retains no images or raster state and may be called concurrently with independent destination arrays.
 * The caller exclusively owns the mutable destination for the duration of [paint].
 */
internal object SampledImageRasterizer {
    /**
     * Paints one validated command using final-density pixel centers and continuous tint multiplication.
     *
     * @param pixels the exclusively owned physical ARGB raster matching [physicalSize].
     * @param physicalSize the positive physical destination extent.
     * @param scale the positive integer logical-to-physical density.
     * @param command the immutable sampled-image command.
     * @param clip the physical clip already intersected with the output viewport.
     * The caller guarantees that clip coordinates fit in [physicalSize].
     * Clipping preserves the source mapping from the original destination and the method retains no arguments.
     */
    fun paint(
        pixels: IntArray,
        physicalSize: IntSize,
        scale: Int,
        command: DrawCommand.SampledImage,
        clip: IntRect,
    ) {
        val destination = command.destination
        val left = maxOf(firstPixel(destination.left, scale), clip.left)
        val top = maxOf(firstPixel(destination.top, scale), clip.top)
        val right = minOf(firstPixel(destination.right, scale), clip.right)
        val bottom = minOf(firstPixel(destination.bottom, scale), clip.bottom)
        if (right <= left || bottom <= top) return
        if (command.tint.value ushr 24 == 0) return

        val color = SampledColor(command.tint.value, command.alphaCutoff)
        if (command.image.size.width == 1 && command.image.size.height == 1) {
            color.paintConstant(pixels, physicalSize.width, left, top, right, bottom, command.image.argbAt(0, 0))
            return
        }
        val sourceXs =
            if (4 <= bottom - top && 4096L <= (right - left).toLong() * (bottom - top)) {
                IntArray(right - left) { offset ->
                    sampleX(left + offset, scale, command)
                }
            } else {
                null
            }
        for (y in top until bottom) {
            val sourceY =
                sampleCoordinate(
                    y,
                    scale,
                    destination.top,
                    destination.bottom,
                    if (command.orientation.flipY) command.source.bottom else command.source.top,
                    if (command.orientation.flipY) command.source.top else command.source.bottom,
                    command.image.size.height,
                )
            var index = y * physicalSize.width + left
            for (x in left until right) {
                val sourceX =
                    sourceXs?.get(x - left) ?: sampleX(x, scale, command)
                pixels[index] = color.blend(command.image.argbAt(sourceX, sourceY), pixels[index])
                index += 1
            }
        }
    }

    private fun sampleX(
        physical: Int,
        scale: Int,
        command: DrawCommand.SampledImage,
    ): Int =
        sampleCoordinate(
            physical,
            scale,
            command.destination.left,
            command.destination.right,
            if (command.orientation.flipX) command.source.right else command.source.left,
            if (command.orientation.flipX) command.source.left else command.source.right,
            command.image.size.width,
        )

    private fun firstPixel(
        edge: Float,
        scale: Int,
    ): Int = ceil(edge.toDouble() * scale.toDouble() - 0.5).toInt()

    private fun sampleCoordinate(
        physical: Int,
        scale: Int,
        destinationStart: Float,
        destinationEnd: Float,
        sourceStart: Float,
        sourceEnd: Float,
        sourceSize: Int,
    ): Int {
        val center = (physical.toFloat() + 0.5f) / scale.toFloat()
        val relative = (center - destinationStart) / (destinationEnd - destinationStart)
        val sample = sourceStart * (1f - relative) + sourceEnd * relative
        return floor(sample).toInt().coerceIn(0, sourceSize - 1)
    }

    private class SampledColor(
        private val tint: Int,
        private val cutoff: Float,
    ) {
        private val identityTint = tint == 0xFFFFFFFF.toInt()
        private val alpha = normalized(tint ushr 24)
        private val red = normalized(tint ushr 16)
        private val green = normalized(tint ushr 8)
        private val blue = normalized(tint)
        private var previousSource = 0
        private var previousDestination = 0
        private var previousResult = 0

        // A whole one-texel image always clamps to (0, 0), including fractional sources and flips.
        // Pass the clipped scalar span without allocating a rectangle for each command.
        @Suppress("LongParameterList")
        fun paintConstant(
            pixels: IntArray,
            width: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            source: Int,
        ) {
            val sourceAlpha = normalized(source ushr 24) * alpha
            if (sourceAlpha < cutoff || sourceAlpha == 0f) return
            if (sourceAlpha == 1f) {
                val result = blend(source, 0)
                for (y in top until bottom) pixels.fill(result, y * width + left, y * width + right)
                return
            }
            var previousDestination = pixels[top * width + left]
            var previousResult = blend(source, previousDestination)
            for (y in top until bottom) {
                for (index in y * width + left until y * width + right) {
                    val destination = pixels[index]
                    if (destination != previousDestination) {
                        previousDestination = destination
                        previousResult = blend(source, destination)
                    }
                    pixels[index] = previousResult
                }
            }
        }

        fun blend(
            source: Int,
            destination: Int,
        ): Int {
            val sourceAlphaByte = source ushr 24
            if (sourceAlphaByte == 0 || alpha == 0f) return destination
            if (sourceAlphaByte == 255 && alpha == 1f) return opaque(source)
            if (source == previousSource && destination == previousDestination) return previousResult
            val sourceAlpha = normalized(source ushr 24) * alpha
            if (sourceAlpha < cutoff || sourceAlpha == 0f) return destination
            val destinationWeight = normalized(destination ushr 24) * (1f - sourceAlpha)
            val outputAlpha = sourceAlpha + destinationWeight
            val alphaByte = quantize(outputAlpha)
            if (alphaByte == 0) return remember(source, destination, 0)
            val outputRed = channel(source ushr 16, destination ushr 16, red, sourceAlpha, destinationWeight, outputAlpha)
            val outputGreen = channel(source ushr 8, destination ushr 8, green, sourceAlpha, destinationWeight, outputAlpha)
            val outputBlue = channel(source, destination, blue, sourceAlpha, destinationWeight, outputAlpha)
            return remember(source, destination, (alphaByte shl 24) or (outputRed shl 16) or (outputGreen shl 8) or outputBlue)
        }

        private fun opaque(source: Int): Int {
            if (identityTint) return source
            if (source == 0xFFFFFFFF.toInt()) return tint
            if (source == previousSource) return previousResult
            val outputRed = quantize(normalized(source ushr 16) * red)
            val outputGreen = quantize(normalized(source ushr 8) * green)
            val outputBlue = quantize(normalized(source) * blue)
            return remember(source, 0, 0xFF000000.toInt() or (outputRed shl 16) or (outputGreen shl 8) or outputBlue)
        }

        // Tint and cutoff are fixed for this command. Opaque results depend only on source;
        // translucent results additionally compare the complete destination, including transparent RGB.
        private fun remember(
            source: Int,
            destination: Int,
            result: Int,
        ): Int {
            previousSource = source
            previousDestination = destination
            previousResult = result
            return result
        }

        private fun channel(
            source: Int,
            destination: Int,
            tint: Float,
            sourceAlpha: Float,
            destinationWeight: Float,
            outputAlpha: Float,
        ): Int = quantize((normalized(source) * tint * sourceAlpha + normalized(destination) * destinationWeight) / outputAlpha)

        private fun normalized(channel: Int): Float = (channel and 0xFF).toFloat() / 255f

        private fun quantize(channel: Float): Int = (channel * 255f).roundToInt().coerceIn(0, 255)
    }
}
