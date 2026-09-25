package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand
import kotlin.math.abs
import kotlin.math.floor

/**
 * Independently maps a sampled font quad to physical pixel centers for the native comparison only.
 * Ambiguities are bounded by measured subpixel precision and float arithmetic at the actual native atlas extent.
 * It never consults a captured image, and cannot enlarge a bound to accommodate a failed pixel.
 */
internal class MinecraftFontRasterSource(
    private val scale: Int,
    private val precision: MinecraftFontGpuPrecision,
) {
    /**
     * Returns possible original source pixels and optional edge exclusion for one quad and physical pixel.
     */
    fun select(
        command: DrawCommand.SampledImage,
        x: Int,
        y: Int,
        ambiguous: Boolean,
    ): Selection {
        val destination = command.destination
        val source = command.source
        val horizontal = axis(x, Axis(destination.left, destination.right, source.left, source.right, command.image.size.width, command.orientation.flipX), ambiguous)
        val vertical = axis(y, Axis(destination.top, destination.bottom, source.top, source.bottom, command.image.size.height, command.orientation.flipY), ambiguous)
        if (horizontal.samples.isEmpty() || vertical.samples.isEmpty()) return Selection(emptyList(), true, false)
        val colors = horizontal.samples.flatMap { sx -> vertical.samples.map { sy -> command.image.argbAt(sx, sy) } }.distinct()
        return Selection(colors, horizontal.maySkip || vertical.maySkip, horizontal.uncertain || vertical.uncertain)
    }

    private fun axis(
        coordinate: Int,
        axis: Axis,
        ambiguous: Boolean,
    ): AxisSelection {
        val physicalCenter = coordinate.toDouble() + 0.5
        val physicalStart = axis.start.toDouble() * scale
        val physicalEnd = axis.end.toDouble() * scale
        val projectionRounding = Math.ulp(maxOf(abs(physicalStart), abs(physicalEnd)).toFloat()).toDouble() * 8.0
        val edgeError = if (ambiguous) precision.subpixelUnit + projectionRounding else 0.0
        if (physicalCenter < physicalStart - edgeError || physicalEnd + edgeError < physicalCenter) return AxisSelection(emptyList(), true, false)
        if (ambiguous.not() && physicalEnd <= physicalCenter) return AxisSelection(emptyList(), true, false)
        val edge = ambiguous && (abs(physicalCenter - physicalStart) <= edgeError || abs(physicalCenter - physicalEnd) <= edgeError)
        val center = (coordinate.toFloat() + 0.5f) / scale.toFloat()
        val relative = (center - axis.start) / (axis.end - axis.start)
        val sourceStart = if (axis.flip) axis.sourceEnd else axis.sourceStart
        val sourceEnd = if (axis.flip) axis.sourceStart else axis.sourceEnd
        val sample = sourceStart * (1f - relative) + sourceEnd * relative
        val rounding =
            if (ambiguous) {
                precision.texelRounding + Math.ulp(sample).toDouble() * 8.0 +
                    projectionRounding * abs((sourceEnd - sourceStart).toDouble() / (physicalEnd - physicalStart))
            } else {
                0.0
            }
        check(rounding < 0.25) { "Native precision cannot establish a bounded nearest-texel proof for this quad." }
        val first = floor(sample.toDouble() - rounding).toInt().coerceIn(0, axis.imageExtent - 1)
        val last = floor(sample.toDouble() + rounding).toInt().coerceIn(0, axis.imageExtent - 1)
        return AxisSelection((first..last).toList(), edge, edge || first != last)
    }

    private data class Axis(
        val start: Float,
        val end: Float,
        val sourceStart: Float,
        val sourceEnd: Float,
        val imageExtent: Int,
        val flip: Boolean,
    )

    private data class AxisSelection(
        val samples: List<Int>,
        val maySkip: Boolean,
        val uncertain: Boolean,
    )

    /**
     * Source alternatives derived before observing a native pixel; an empty list means no covered sample.
     */
    data class Selection(
        val colors: List<Int>,
        val maySkip: Boolean,
        val uncertain: Boolean,
    )
}
