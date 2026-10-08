package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.math.BigInteger

/**
 * Independent whole-raster reference using unbounded rational centers and Long source-over numerators.
 * It reads real immutable images and tests clip membership against centers, without any production raster helper.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object IntegerBlitReference {
    /**
     * Applies the complete fixed integer corpus in order within absolute region coordinates.
     */
    fun paint(commands: List<DrawCommand>, bounds: IntRect, density: Int): IntArray {
        val target = Target(bounds, density)
        val clips = ArrayList<DrawCommand>()
        for (command in commands) {
            when (command) {
                is DrawCommand.PushClip, is DrawCommand.PushFractionalClip -> clips.add(command)
                DrawCommand.PopClip -> clips.removeAt(clips.lastIndex)
                is DrawCommand.FillRectangle -> target.fill(command, clips)
                is DrawCommand.BlitImage -> target.blit(Blit(command.image, command.source, command.destination, false), clips)
                is DrawCommand.BlitImagePixels -> target.blit(Blit(command.image, command.source, command.destination, true), clips)
                else -> error("This independent fixture reference accepts only integer primitives and clips.")
            }
        }
        check(clips.isEmpty())
        return target.pixels
    }

    /**
     * Maps an absolute integer or physical position through an exact rational center.
     * The caller supplies density one for logical-cell sampling.
     */
    fun coordinate(
        position: Int,
        sourceStart: Int,
        sourceExtent: Int,
        destination: IntRange,
        density: Int,
    ): Int {
        val two = BigInteger.valueOf(2)
        val origin = BigInteger.valueOf(destination.first.toLong()).multiply(BigInteger.valueOf(density.toLong()))
        val center = BigInteger.valueOf(position.toLong()).subtract(origin).multiply(two).add(BigInteger.ONE)
        val numerator = center.multiply(BigInteger.valueOf(sourceExtent.toLong()))
        val denominator = BigInteger.valueOf(destination.last.toLong() - destination.first + 1).multiply(BigInteger.valueOf(density.toLong())).multiply(two)
        return numerator.divide(denominator).add(BigInteger.valueOf(sourceStart.toLong())).intValueExact()
    }

    /**
     * Computes ordered straight-ARGB composition independently with full Long numerators and half-up division.
     */
    fun blend(source: Int, destination: Int): Int {
        val sa = (source ushr 24).toLong()
        val da = (destination ushr 24).toLong()
        val alpha = sa * 255 + da * (255 - sa)
        if (alpha == 0L) return 0
        var result = (((alpha + 127) / 255).toInt() shl 24)
        for (shift in 16 downTo 0 step 8) {
            val sc = (source ushr shift and 255).toLong()
            val dc = (destination ushr shift and 255).toLong()
            val numerator = sc * sa * 255 + dc * da * (255 - sa)
            result = result or (((numerator + alpha / 2) / alpha).toInt() shl shift)
        }
        return result
    }

    private data class Blit(val image: DrawImage, val source: IntRect, val destination: IntRect, val physical: Boolean)

    private class Target(val bounds: IntRect, val density: Int) {
        private val width = bounds.width * density
        private val height = bounds.height * density
        val pixels = IntArray(width * height)

        fun fill(command: DrawCommand.FillRectangle, clips: List<DrawCommand>) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val absoluteX = x + bounds.left * density
                    val absoluteY = y + bounds.top * density
                    if (inside(command.bounds, absoluteX, absoluteY) && clipped(clips, absoluteX, absoluteY)) {
                        val index = y * width + x
                        pixels[index] = blend(command.color.value, pixels[index])
                    }
                }
            }
        }

        fun blit(command: Blit, clips: List<DrawCommand>) {
            val source = command.source
            val destination = command.destination
            val sampleDensity = if (command.physical) density else 1
            val columns =
                IntArray(width) { x ->
                    val absolute = x + bounds.left * density
                    if (destination.left <= absolute / density && absolute / density < destination.right) {
                        coordinate(if (command.physical) absolute else absolute / density, source.left, source.width, destination.left until destination.right, sampleDensity)
                    } else {
                        0
                    }
                }
            for (y in 0 until height) {
                val absoluteY = y + bounds.top * density
                if (absoluteY / density < destination.top || destination.bottom <= absoluteY / density) continue
                val sourceY = coordinate(if (command.physical) absoluteY else absoluteY / density, source.top, source.height, destination.top until destination.bottom, sampleDensity)
                for (x in 0 until width) {
                    val absoluteX = x + bounds.left * density
                    if (inside(destination, absoluteX, absoluteY) && clipped(clips, absoluteX, absoluteY)) {
                        val index = y * width + x
                        pixels[index] = blend(command.image.argbAt(columns[x], sourceY), pixels[index])
                    }
                }
            }
        }

        private fun inside(rect: IntRect, x: Int, y: Int): Boolean =
            rect.left <= x / density && x / density < rect.right && rect.top <= y / density && y / density < rect.bottom

        private fun clipped(clips: List<DrawCommand>, x: Int, y: Int): Boolean =
            clips.all { clip ->
                when (clip) {
                    is DrawCommand.PushClip -> inside(clip.bounds, x, y)

                    is DrawCommand.PushFractionalClip -> {
                        val centerX = (x.toDouble() + 0.5) / density
                        val centerY = (y.toDouble() + 0.5) / density
                        clip.bounds.left <= centerX && centerX < clip.bounds.right && clip.bounds.top <= centerY && centerY < clip.bounds.bottom
                    }

                    else -> error("Expected a clip ancestor.")
                }
            }
    }
}
