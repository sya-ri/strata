package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Admits fractional source rectangles only when both axes' physical samples stay away from nearest-texel boundaries.
 * CPU Float sampling and normalized-UV interpolation must select the same texel, with an additional rounding margin.
 * Physical destination edges must be integer-aligned so this expanded subset does not depend on fractional vertex rounding.
 * Each changed frame checks at most 4,096 physical samples per axis, without reading or retaining source pixels.
 * Larger or unrepresentable axes use the existing portable fallback; entirely integer source rectangles retain their established path.
 */
@JvmSynthetic
internal fun hasStableFabricFractionalSourceSampling(
    command: DrawCommand.SampledImage,
    scale: Int,
): Boolean =
    stableFractionalSourceAxis(command.source.left, command.source.right, command.image.size.width, command.destination.left, command.destination.right, scale) &&
        stableFractionalSourceAxis(command.source.top, command.source.bottom, command.image.size.height, command.destination.top, command.destination.bottom, scale)

private fun stableFractionalSourceAxis(
    sourceStart: Float,
    sourceEnd: Float,
    sourceSize: Int,
    destinationStart: Float,
    destinationEnd: Float,
    scale: Int,
): Boolean {
    val physicalStart = destinationStart.toDouble() * scale
    val physicalEnd = destinationEnd.toDouble() * scale
    if (physicalStart != floor(physicalStart) || physicalEnd != floor(physicalEnd)) return false
    val first = ceil(destinationStart.toDouble() * scale - 0.5)
    val last = ceil(destinationEnd.toDouble() * scale - 0.5)
    // Keep the preparation scan bounded and Float pixel centers representable even for off-screen input geometry.
    if (first < -1_048_576.0 || 1_048_576.0 < last) return false
    if (last - first < 0.0 || 4_096.0 < last - first) return false
    val size = sourceSize.toFloat()
    val normalizedStart = sourceStart / size
    val normalizedEnd = sourceEnd / size
    // Reserve a texel-coordinate rounding margin in addition to Float normalization/interpolation error.
    val margin = maxOf(1f / 256f, Math.ulp(size) * 16f)
    for (physical in first.toInt() until last.toInt()) {
        val center = (physical.toFloat() + 0.5f) / scale.toFloat()
        val relative = (center - destinationStart) / (destinationEnd - destinationStart)
        val sample = sourceStart * (1f - relative) + sourceEnd * relative
        val nativeSample = (normalizedStart * (1f - relative) + normalizedEnd * relative) * size
        val texel = floor(sample)
        if (floor(nativeSample) != texel || sample - texel <= margin || texel + 1f - sample <= margin) return false
    }
    return true
}
