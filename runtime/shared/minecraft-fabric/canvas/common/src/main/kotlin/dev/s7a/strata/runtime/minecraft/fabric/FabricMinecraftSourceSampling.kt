package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.render.DrawCommand
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Admits source rectangles only when both axes' physical samples stay away from nearest-texel boundaries.
 * CPU Float sampling and normalized-UV interpolation must select the same texel, with an additional rounding margin.
 * Newly admitted fractional sources additionally require integer-aligned physical destination edges.
 * Each changed frame checks at most 4,096 physical samples per axis, without reading or retaining source pixels.
 * Larger or unrepresentable axes are ineligible for ordinary native quads, including ambiguous integer source rectangles.
 */
@JvmSynthetic
internal fun hasStableFabricSourceSampling(
    command: DrawCommand.SampledImage,
    scale: Int,
    alignedEdges: Boolean = true,
): Boolean =
    stableFractionalSourceAxis(command.source.left, command.source.right, command.image.size.width, command.destination.left, command.destination.right, scale, alignedEdges) &&
        stableFractionalSourceAxis(command.source.top, command.source.bottom, command.image.size.height, command.destination.top, command.destination.bottom, scale, alignedEdges)

private fun stableFractionalSourceAxis(
    sourceStart: Float,
    sourceEnd: Float,
    sourceSize: Int,
    destinationStart: Float,
    destinationEnd: Float,
    scale: Int,
    alignedEdges: Boolean,
): Boolean {
    val physicalStart = destinationStart.toDouble() * scale
    val physicalEnd = destinationEnd.toDouble() * scale
    if (alignedEdges && (physicalStart != floor(physicalStart) || physicalEnd != floor(physicalEnd))) return false
    val first = ceil(destinationStart.toDouble() * scale - 0.5)
    val last = ceil(destinationEnd.toDouble() * scale - 0.5)
    // Keep the preparation scan bounded and Float pixel centers representable even for off-screen input geometry.
    if (first < -1_048_576.0 || 1_048_576.0 < last) return false
    if ((last - first in 0.0..4_096.0).not()) return false
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

/**
 * Checks the platform-independent direct subset before any native texture-capacity lookup.
 *
 * @param command immutable sampled command whose constructor already validates source containment.
 * @param scale positive physical density used to reject ambiguous native quad-edge ownership at pixel centers.
 * @param fractionalSource whether the adapter submits floating source UVs without converting source extents to integers.
 * @param exactSampling whether the adapter supports bounded exact axis lookup, opaque RGB masks and CPU-decided cutoffs.
 * @return true when native nearest sampling can preserve its source and compositing contract.
 */
@JvmSynthetic
internal fun isDirectFabricSampledImage(
    command: DrawCommand.SampledImage,
    scale: Int = 1,
    fractionalSource: Boolean = false,
    exactSampling: Boolean = false,
): Boolean {
    if (exactSampling && command.tint.hasExactFabricSamplingTint()) {
        val destination = command.destination
        val bounded = ceil(destination.right.toDouble()) - floor(destination.left.toDouble()) <= 4_096.0 / scale && ceil(destination.bottom.toDouble()) - floor(destination.top.toDouble()) <= 4_096.0 / scale
        if (bounded) return true
    }
    return isOrdinaryFabricSampledImage(command, scale, fractionalSource || exactSampling)
}

private fun isOrdinaryFabricSampledImage(
    command: DrawCommand.SampledImage,
    scale: Int,
    fractionalSource: Boolean,
): Boolean {
    if (command.tint != ArgbColor(-1) || command.alphaCutoff != 0f) return false
    val integerSource = command.source.left.isWholeTexel() && command.source.top.isWholeTexel() && command.source.right.isWholeTexel() && command.source.bottom.isWholeTexel()
    if ((integerSource || fractionalSource).not()) return false
    val destination = command.destination
    if (command.orientation != SampledImageOrientation.Normal) return false
    if (destination.hasFabricPhysicalCenterEdge(scale)) return false
    return hasStableFabricSourceSampling(command, scale, alignedEdges = integerSource.not())
}

/**
 * Admits only opaque channel masks, whose multiplication leaves each source channel unchanged or zero.
 * Intermediate channel values and alpha modulation retain CPU Float composition without early quantization.
 */
@JvmSynthetic
internal fun ArgbColor.hasExactFabricSamplingTint(): Boolean {
    if (value ushr 24 != 255) return false
    val red = value ushr 16 and 255
    val green = value ushr 8 and 255
    val blue = value and 255
    return (red == 0 || red == 255) && (green == 0 || green == 255) && (blue == 0 || blue == 255)
}

private fun Float.isWholeTexel(): Boolean = toDouble() == floor(toDouble())
