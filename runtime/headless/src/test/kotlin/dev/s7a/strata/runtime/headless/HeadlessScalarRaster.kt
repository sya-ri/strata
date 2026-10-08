package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Ordered per-pixel oracle without the production clip conversion, coverage guards, palettes or uniform state.
 * Coverage compares absolute physical centers directly with the original integer or Float edges.
 * Source selection retains the specified logical, rational physical and ordered Float interpolation contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object HeadlessScalarRaster {
    /**
     * Returns newly owned transparent-black output for a valid balanced command list and nonnegative region.
     * Only small test fixtures are admitted; integer coordinate products fit in Long.
     */
    @Suppress("NestedBlockDepth") // The oracle deliberately visits every ordered command and physical pixel.
    fun paint(
        commands: List<DrawCommand>,
        bounds: IntRect,
        scale: Int,
    ): IntArray {
        val width = bounds.width * scale
        val pixels = IntArray(width * bounds.height * scale)
        val clips = ArrayList<DrawCommand>()
        for (command in commands) {
            when (command) {
                is DrawCommand.PushClip, is DrawCommand.PushFractionalClip -> clips.add(command)
                DrawCommand.PopClip -> clips.removeAt(clips.lastIndex)
                is DrawCommand.Platform -> error("The portable oracle does not accept platform commands.")
                else -> {
                    for (index in pixels.indices) {
                        val px = bounds.left * scale + index % width
                        val py = bounds.top * scale + index / width
                        val x = (px.toDouble() + 0.5) / scale
                        val y = (py.toDouble() + 0.5) / scale
                        if (clips.all { contains(it, x, y) } && contains(command, x, y)) {
                            pixels[index] = compose(command, px, py, scale, pixels[index])
                        }
                    }
                }
            }
        }
        check(clips.isEmpty())
        return pixels
    }

    private fun contains(
        command: DrawCommand,
        x: Double,
        y: Double,
    ): Boolean =
        when (command) {
            is DrawCommand.FillRectangle -> contains(command.bounds, x, y)
            is DrawCommand.BlitImage -> contains(command.destination, x, y)
            is DrawCommand.BlitImagePixels -> contains(command.destination, x, y)
            is DrawCommand.PushClip -> contains(command.bounds, x, y)
            is DrawCommand.SampledImage -> command.destination.let { it.left <= x && x < it.right && it.top <= y && y < it.bottom }
            is DrawCommand.PushFractionalClip -> command.bounds.let { it.left <= x && x < it.right && it.top <= y && y < it.bottom }
            else -> error("A pixel command or clip is required.")
        }

    private fun contains(
        bounds: IntRect,
        x: Double,
        y: Double,
    ): Boolean = bounds.left <= x && x < bounds.right && bounds.top <= y && y < bounds.bottom

    private fun compose(
        command: DrawCommand,
        px: Int,
        py: Int,
        scale: Int,
        destination: Int,
    ): Int =
        when (command) {
            is DrawCommand.FillRectangle -> blendInteger(command.color.value, destination)
            is DrawCommand.BlitImage -> {
                val x = sourceAt(px / scale, command.destination.left, command.destination.width, command.source.left, command.source.width, 1)
                val y = sourceAt(py / scale, command.destination.top, command.destination.height, command.source.top, command.source.height, 1)
                blendInteger(command.image.argbAt(x, y), destination)
            }
            is DrawCommand.BlitImagePixels -> {
                val x = sourceAt(px, command.destination.left, command.destination.width, command.source.left, command.source.width, scale)
                val y = sourceAt(py, command.destination.top, command.destination.height, command.source.top, command.source.height, scale)
                blendInteger(command.image.argbAt(x, y), destination)
            }
            is DrawCommand.SampledImage -> {
                val rx = ((px.toFloat() + 0.5f) / scale - command.destination.left) / command.destination.width
                val ry = ((py.toFloat() + 0.5f) / scale - command.destination.top) / command.destination.height
                val sx = if (command.orientation.flipX) command.source.right * (1f - rx) + command.source.left * rx else command.source.left * (1f - rx) + command.source.right * rx
                val sy = if (command.orientation.flipY) command.source.bottom * (1f - ry) + command.source.top * ry else command.source.top * (1f - ry) + command.source.bottom * ry
                val x = floor(sx).toInt().coerceIn(0, command.image.size.width - 1)
                val y = floor(sy).toInt().coerceIn(0, command.image.size.height - 1)
                blendSampled(command.image.argbAt(x, y), destination, command.tint.value, command.alphaCutoff)
            }
            else -> {
                error("A pixel command is required.")
            }
        }

    @Suppress("LongParameterList") // The rational coordinate proof names the original source and destination axes separately.
    private fun sourceAt(
        physical: Int,
        start: Int,
        extent: Int,
        sourceStart: Int,
        sourceExtent: Int,
        scale: Int,
    ): Int = sourceStart + (((physical.toLong() - start.toLong() * scale) * 2 + 1) * sourceExtent / (extent.toLong() * scale * 2)).toInt()

    /**
     * Uses independent Long source-over numerators and half-up division, including canonical zero-alpha output.
     */
    fun blendInteger(
        source: Int,
        destination: Int,
    ): Int {
        val sa = (source ushr 24).toLong()
        val da = (destination ushr 24).toLong()
        val alpha = sa * 255 + da * (255 - sa)
        if (alpha == 0L) return 0
        var result = ((alpha + 127) / 255).toInt() shl 24
        for (shift in listOf(16, 8, 0)) {
            val sc = (source ushr shift and 255).toLong()
            val dc = (destination ushr shift and 255).toLong()
            val numerator = sc * sa * 255 + dc * da * (255 - sa)
            result = result or (((numerator + alpha / 2) / alpha).toInt() shl shift)
        }
        return result
    }

    private fun blendSampled(
        source: Int,
        destination: Int,
        tint: Int,
        cutoff: Float,
    ): Int {
        val alpha = channel(source, 24) * channel(tint, 24)
        if (alpha < cutoff || alpha == 0f) return destination
        val weight = channel(destination, 24) * (1f - alpha)
        val outputAlpha = alpha + weight
        val alphaByte = (outputAlpha * 255f).roundToInt().coerceIn(0, 255)
        if (alphaByte == 0) return 0
        var result = alphaByte shl 24
        for (shift in listOf(16, 8, 0)) {
            val value = (channel(source, shift) * channel(tint, shift) * alpha + channel(destination, shift) * weight) / outputAlpha
            result = result or ((value * 255f).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

    private fun channel(
        color: Int,
        shift: Int,
    ): Float = (color ushr shift and 255).toFloat() / 255f
}
