package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Frozen original scalar producer and independent pixel-center/straight-ARGB reference.
 * Neither construction nor rendering calls the new tiling bridge, compactor or headless rasterizer.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftTileBackgroundReference {
    /**
     * Reproduces the original checked row-major producer, including terminal stride increments.
     */
    fun scalar(
        image: DrawImage,
        size: IntSize,
    ): List<DrawCommand.BlitImage> =
        buildList {
            var top = 0
            while (top < size.height) {
                var left = 0
                while (left < size.width) {
                    val width = minOf(image.size.width, size.width - left)
                    val height = minOf(image.size.height, size.height - top)
                    add(DrawCommand.BlitImage(image, IntRect(0, 0, width, height), IntRect(left, top, Math.addExact(left, width), Math.addExact(top, height))))
                    left = Math.addExact(left, image.size.width)
                }
                top = Math.addExact(top, image.size.height)
            }
        }

    /**
     * Maps each original cell through the documented centered uniform fit using unchanged Double-to-Float edges.
     */
    fun fitted(
        image: DrawImage,
        design: IntSize,
        viewport: IntSize,
        offset: IntOffset = IntOffset.Zero,
    ): List<DrawCommand.SampledImage> {
        val scale = minOf(viewport.width.toDouble() / design.width, viewport.height.toDouble() / design.height)
        val x = offset.x + (viewport.width - design.width * scale) / 2.0
        val y = offset.y + (viewport.height - design.height * scale) / 2.0
        return scalar(image, design).map { blit ->
            val local = blit.destination
            val source = blit.source
            DrawCommand.SampledImage(
                image,
                FloatRect(source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat()),
                FloatRect((x + scale * local.left).toFloat(), (y + scale * local.top).toFloat(), (x + scale * local.right).toFloat(), (y + scale * local.bottom).toFloat()),
                ArgbColor(-1),
                0f,
            )
        }
    }

    /**
     * Executes ordered integer and fractional image commands and nested clips at physical pixel centers.
     * Reference storage belongs only to this invocation; alpha rounding follows the canonical rendering contract.
     */
    fun pixels(
        commands: List<DrawCommand>,
        viewport: IntSize,
        density: Int,
    ): IntArray {
        val width = Math.multiplyExact(viewport.width, density)
        val height = Math.multiplyExact(viewport.height, density)
        val pixels = IntArray(Math.multiplyExact(width, height))
        val clips = mutableListOf(FloatRect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()))
        val dimensions = width to height
        for (command in commands) {
            when (command) {
                is DrawCommand.PushClip -> clips.add(FloatRect(command.bounds.left.toFloat(), command.bounds.top.toFloat(), command.bounds.right.toFloat(), command.bounds.bottom.toFloat()))
                is DrawCommand.PushFractionalClip -> clips.add(command.bounds)
                DrawCommand.PopClip -> clips.removeAt(clips.lastIndex)
                is DrawCommand.FillRectangle, is DrawCommand.BlitImage, is DrawCommand.SampledImage -> paint(command, pixels, dimensions, density, clips)
                else -> error("Reference expects only ordinary tiled image commands and clips")
            }
        }
        check(clips.size == 1)
        return pixels
    }

    /**
     * Preserves ordered source sampling and source-over writes independently of the production rasterizer.
     */
    private fun paint(
        command: DrawCommand,
        pixels: IntArray,
        dimensions: Pair<Int, Int>,
        density: Int,
        clips: List<FloatRect>,
    ) {
        val (width, height) = dimensions
        val destination = destinationOf(command)
        val left = maxOf(0, ceil(destination.left.toDouble() * density - 0.5).toInt())
        val top = maxOf(0, ceil(destination.top.toDouble() * density - 0.5).toInt())
        val right = minOf(width, ceil(destination.right.toDouble() * density - 0.5).toInt())
        val bottom = minOf(height, ceil(destination.bottom.toDouble() * density - 0.5).toInt())
        for (py in top until bottom) {
            for (px in left until right) {
                val cx = (px.toDouble() + 0.5) / density
                val cy = (py.toDouble() + 0.5) / density
                if (outsideClips(clips, cx, cy)) continue
                val source =
                    when (command) {
                        is DrawCommand.FillRectangle -> {
                            command.color.value
                        }

                        is DrawCommand.BlitImage -> {
                            val x = command.source.left + ((2L * (px / density - command.destination.left) + 1L) * command.source.width / (2L * command.destination.width)).toInt()
                            val y = command.source.top + ((2L * (py / density - command.destination.top) + 1L) * command.source.height / (2L * command.destination.height)).toInt()
                            command.image.argbAt(x, y)
                        }

                        is DrawCommand.SampledImage -> {
                            val x = sampled(px, density, command.destination.left, command.destination.right, command.source.left, command.source.right, command.image.size.width)
                            val y = sampled(py, density, command.destination.top, command.destination.bottom, command.source.top, command.source.bottom, command.image.size.height)
                            command.image.argbAt(x, y)
                        }

                        else -> {
                            error("Unreachable image kind")
                        }
                    }
                val index = py * width + px
                pixels[index] = if (command is DrawCommand.SampledImage) fractionalBlend(source, pixels[index]) else integerBlend(source, pixels[index])
            }
        }
    }

    private fun destinationOf(command: DrawCommand): FloatRect =
        when (command) {
            is DrawCommand.FillRectangle -> command.bounds.let { FloatRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            is DrawCommand.BlitImage -> command.destination.let { FloatRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            is DrawCommand.SampledImage -> command.destination
            else -> error("Unreachable image kind")
        }

    private fun outsideClips(
        clips: List<FloatRect>,
        cx: Double,
        cy: Double,
    ): Boolean =
        clips.any { clip ->
            if (cx < clip.left || clip.right <= cx) true else cy < clip.top || clip.bottom <= cy
        }

    /**
     * Computes an independent modulo image over a heterogeneous background at integer density.
     */
    fun modulo(
        image: DrawImage,
        background: DrawImage,
        viewport: IntSize,
        density: Int,
    ): IntArray =
        IntArray(viewport.width * viewport.height * density * density) { index ->
            val width = viewport.width * density
            val x = index % width / density
            val y = index / width / density
            integerBlend(image.argbAt(x % image.size.width, y % image.size.height), integerBlend(background.argbAt(x, y), 0))
        }

    @Suppress("LongParameterList") // The independent sampler preserves explicit source and destination edges without allocating a mapping object.
    private fun sampled(pixel: Int, density: Int, start: Float, end: Float, sourceStart: Float, sourceEnd: Float, extent: Int): Int {
        val center = (pixel.toFloat() + 0.5f) / density.toFloat()
        val position = (center - start) / (end - start)
        return floor(sourceStart * (1f - position) + sourceEnd * position).toInt().coerceIn(0, extent - 1)
    }

    private fun integerBlend(
        source: Int,
        destination: Int,
    ): Int {
        val sa = source ushr 24
        val da = destination ushr 24
        val alpha = sa.toLong() * 255 + da.toLong() * (255 - sa)
        if (alpha == 0L) return 0

        fun channel(shift: Int): Int {
            val numerator = (source ushr shift and 255).toLong() * sa * 255 + (destination ushr shift and 255).toLong() * da * (255 - sa)
            return ((numerator + alpha / 2) / alpha).toInt()
        }
        return (((alpha + 127) / 255).toInt() shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun fractionalBlend(
        source: Int,
        destination: Int,
    ): Int {
        val sa = (source ushr 24).toFloat() / 255f
        if (sa == 0f) return destination
        if (sa == 1f) return source
        val weight = (destination ushr 24).toFloat() / 255f * (1f - sa)
        val alpha = sa + weight

        fun channel(shift: Int): Int {
            val value = ((source ushr shift and 255).toFloat() / 255f * sa + (destination ushr shift and 255).toFloat() / 255f * weight) / alpha
            return (value * 255f).roundToInt().coerceIn(0, 255)
        }
        return ((alpha * 255f).roundToInt() shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
