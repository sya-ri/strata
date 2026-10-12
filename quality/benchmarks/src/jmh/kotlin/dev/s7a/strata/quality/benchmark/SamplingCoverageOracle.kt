@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Independent scalar pixel-center oracle, with the original Float interpolation order and separate Double coverage.
 * It derives expected source colors from the frozen pattern formula rather than reading runtime-selected texels.
 * Complete metadata checks include RGBA bytes in all three rows, uncovered entries, padding and extent/effect texels.
 */
public object SamplingCoverageOracle {
    /**
     * Checks a complete genuine map against independently calculated integer selections and RGBA bytes.
     */
    public fun metadata(
        runtime: SamplingCoverageRuntime,
        map: Any,
        command: DrawCommand.SampledImage,
        bounds: IntRect,
        density: Int,
    ) {
        val width = Math.multiplyExact(bounds.width, density)
        val height = Math.multiplyExact(bounds.height, density)
        val indices = runtime.indices(map)
        check(runtime.size(map).width == width && runtime.size(map).height == height)
        check(indices.size.width == maxOf(3, width, height) && indices.size.height == 3)
        for (row in 0..2) {
            for (position in 0 until indices.size.width) {
                val expected =
                    when (row) {
                        0 -> {
                            if (position < width) select(Math.addExact(Math.multiplyExact(bounds.left, density), position), command, density, horizontal = true) + 1 else 0
                        }

                        1 -> {
                            if (position < height) select(Math.addExact(Math.multiplyExact(bounds.top, density), position), command, density, horizontal = false) + 1 else 0
                        }

                        else -> {
                            when (position) {
                                0 -> width + 1
                                1 -> height + 1
                                2 -> effect(command)
                                else -> 0
                            }
                        }
                    }
                val actual = indices.argbAt(position, row)
                check(actual ushr 16 and 255 == expected and 255) { "R row=$row position=$position" }
                check(actual ushr 8 and 255 == expected ushr 8 and 255) { "G row=$row position=$position" }
                check(actual and 255 == expected ushr 16 and 255) { "B row=$row position=$position" }
                check(actual ushr 24 == expected ushr 24) { "A row=$row position=$position" }
            }
        }
    }

    /**
     * Checks complete output at the real nonnegative viewport, including clips, fills and ordered sampled effects.
     */
    public fun completePixels(
        input: SamplingCoverageFixture.Input,
        trace: List<SamplingCoverageRuntime.Trace>? = null,
    ) {
        val actual = rasterizeHeadless(input.commands, input.viewport, input.density)
        for (y in 0 until actual.size.height) {
            for (x in 0 until actual.size.width) {
                val expected = pixel(input.commands, x, y, input.density)
                check(actual.argbAt(x, y) == expected) { "Complete pixel ($x,$y) density=${input.density}" }
                if (trace != null) {
                    val prepared = preparedPixel(trace, x, y, input.density)
                    check(prepared == expected) { "Actual ordered preparation trace changed pixel ($x,$y)" }
                }
            }
        }
    }

    /**
     * Composes the unchanged ordered prepared trace at one physical pixel center.
     */
    private fun preparedPixel(
        trace: List<SamplingCoverageRuntime.Trace>,
        x: Int,
        y: Int,
        density: Int,
    ): Int {
        var prepared = 0
        for (layer in trace) {
            val bounds = layer.bounds
            if (covered(x, y, bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble(), density)) prepared = pixel(layer.commands, x, y, density, prepared)
        }
        return prepared
    }

    /**
     * Returns a zero-based source coordinate or minus one for uncovered physical centers.
     */
    public fun select(
        physical: Int,
        command: DrawCommand.SampledImage,
        density: Int,
        horizontal: Boolean,
    ): Int {
        val destination = command.destination
        val source = command.source
        val start = if (horizontal) destination.left else destination.top
        val end = if (horizontal) destination.right else destination.bottom
        val first = ceil(start.toDouble() * density - 0.5)
        val last = ceil(end.toDouble() * density - 0.5)
        val centerDouble = physical.toDouble() + 0.5
        val covered = start.toDouble() * density <= centerDouble && centerDouble < end.toDouble() * density
        check(covered == (first <= physical.toDouble() && physical.toDouble() < last))
        if (covered.not()) return -1
        val flipped = if (horizontal) command.orientation.flipX else command.orientation.flipY
        val lower = if (horizontal) source.left else source.top
        val upper = if (horizontal) source.right else source.bottom
        val sourceStart = if (flipped) upper else lower
        val sourceEnd = if (flipped) lower else upper
        val center = (physical.toFloat() + 0.5f) / density.toFloat()
        val relative = (center - start) / (end - start)
        val interpolated = sourceStart * (1f - relative) + sourceEnd * relative
        val extent = if (horizontal) command.image.size.width else command.image.size.height
        return floor(interpolated).toInt().coerceIn(0, extent - 1)
    }

    private fun effect(command: DrawCommand.SampledImage): Int {
        val tint = command.tint.value
        val mask = (if (tint ushr 16 and 255 == 255) 1 else 0) + (if (tint ushr 8 and 255 == 255) 2 else 0) + (if (tint and 255 == 255) 4 else 0)
        return (if (command.alphaCutoff == 1f) 255 * 8 else 0) + mask
    }

    @Suppress("CyclomaticComplexMethod") // The independent scalar oracle executes the complete ordered fixture command trace.
    private fun pixel(commands: List<DrawCommand>, x: Int, y: Int, density: Int, initial: Int = 0): Int {
        var result = initial
        val clips = ArrayList<Boolean>()
        for (command in commands) {
            when (command) {
                is DrawCommand.PushClip -> {
                    clips.add(covered(x, y, command.bounds.left.toDouble(), command.bounds.top.toDouble(), command.bounds.right.toDouble(), command.bounds.bottom.toDouble(), density))
                }

                is DrawCommand.PushFractionalClip -> {
                    clips.add(covered(x, y, command.bounds.left.toDouble(), command.bounds.top.toDouble(), command.bounds.right.toDouble(), command.bounds.bottom.toDouble(), density))
                }

                DrawCommand.PopClip -> {
                    clips.removeAt(clips.lastIndex)
                }

                is DrawCommand.SampledImage -> {
                    if (clips.none { it.not() }) {
                        val sx = select(x, command, density, horizontal = true)
                        val sy = select(y, command, density, horizontal = false)
                        if (0 <= sx && 0 <= sy) result = sampledBlend(SamplingCoverageFixture.pattern(sx, sy), command, result)
                    }
                }

                is DrawCommand.FillRectangle -> {
                    if (clips.none { it.not() }) {
                        val bounds = command.bounds
                        if (covered(x, y, bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble(), density)) result = integerBlend(command.color.value, result)
                    }
                }

                else -> {
                    error("The frozen scalar corpus contains only fills, clips and sampled images.")
                }
            }
        }
        check(clips.isEmpty())
        return result
    }

    @Suppress("LongParameterList") // Keep every independently frozen edge and both physical coordinates explicit.
    private fun covered(x: Int, y: Int, left: Double, top: Double, right: Double, bottom: Double, density: Int): Boolean =
        left * density <= x + 0.5 && x + 0.5 < right * density && top * density <= y + 0.5 && y + 0.5 < bottom * density

    private fun sampledBlend(
        source: Int,
        command: DrawCommand.SampledImage,
        destination: Int,
    ): Int {
        val tint = command.tint.value
        val alpha = (source ushr 24).toFloat() / 255f * ((tint ushr 24).toFloat() / 255f)
        if (alpha < command.alphaCutoff || alpha == 0f) return destination
        val oldAlpha = (destination ushr 24).toFloat() / 255f
        val outputAlpha = alpha + oldAlpha * (1f - alpha)
        var result = floor(outputAlpha * 255f + 0.5f).toInt() shl 24
        for (shift in listOf(16, 8, 0)) {
            val channel = ((source ushr shift and 255).toFloat() / 255f) * ((tint ushr shift and 255).toFloat() / 255f)
            val oldChannel = (destination ushr shift and 255).toFloat() / 255f
            val output = (channel * alpha + oldChannel * oldAlpha * (1f - alpha)) / outputAlpha
            result = result or (floor(output * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

    private fun integerBlend(
        source: Int,
        destination: Int,
    ): Int {
        val alpha = source ushr 24
        val oldAlpha = destination ushr 24
        val numerator = alpha * 255L + oldAlpha * (255L - alpha)
        if (numerator == 0L) return 0
        var result = ((numerator + 127L) / 255L).toInt() shl 24
        for (shift in listOf(16, 8, 0)) {
            val weighted = (source ushr shift and 255) * alpha * 255L + (destination ushr shift and 255) * oldAlpha * (255L - alpha)
            result = result or (((weighted + numerator / 2) / numerator).toInt() shl shift)
        }
        return result
    }
}
