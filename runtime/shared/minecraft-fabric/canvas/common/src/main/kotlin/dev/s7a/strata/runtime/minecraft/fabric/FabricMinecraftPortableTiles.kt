@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Splits large CPU runs into at most 64 disjoint regions, allowing existing generation matching to reuse unchanged pixels.
 * Each region replays intersecting primitives with their original coordinates and balanced original clips.
 * Small runs keep their single tight image; large regions target 256 physical pixels per edge and grow to respect the bound.
 * Admission counts describe original command occurrences once, independently of duplicated tile coverage.
 * These render-thread descriptions contain no pixels or native resources and live only with the current prepared frame.
 */
@JvmSynthetic
internal fun tileFabricMinecraftPortable(
    commands: List<DrawCommand>,
    bounds: IntRect,
    scale: Int,
    ineligibleSampledImages: Int = 0,
    capacitySampledImages: Int = 0,
): List<FabricMinecraftFrameLayer.Portable> {
    require(0 < scale) { "Minecraft GUI scale must be positive." }
    val width = Math.multiplyExact(bounds.width, scale)
    val height = Math.multiplyExact(bounds.height, scale)
    Math.multiplyExact(bounds.right, scale)
    Math.multiplyExact(bounds.bottom, scale)
    if (width.toLong() * height <= 262_144L) {
        val absolute = commands.any { it is DrawCommand.SampledImage }
        val detached = if (absolute) commands.toList() else localizeFabricPortable(commands, bounds)
        return listOf(FabricMinecraftFrameLayer.Portable(detached, bounds, ineligibleSampledImages, absolute, capacitySampledImages))
    }
    var extent = maxOf(1, 256 / scale)
    val orderingGroup = Any()

    fun count(): Long = ((bounds.width.toLong() + extent - 1) / extent) * ((bounds.height.toLong() + extent - 1) / extent)
    while (64L < count()) extent = Math.multiplyExact(extent, 2)
    var ineligibleCount = ineligibleSampledImages
    var capacityCount = capacitySampledImages
    var tintCount = commands.count { it is DrawCommand.SampledImage && it.tint != ArgbColor(-1) }
    var cutoffCount = commands.count { it is DrawCommand.SampledImage && it.tint == ArgbColor(-1) && it.alphaCutoff != 0f }
    val result = ArrayList<FabricMinecraftFrameLayer.Portable>()
    var top = bounds.top
    while (top < bounds.bottom) {
        val bottom = top + minOf(extent, bounds.bottom - top)
        var left = bounds.left
        while (left < bounds.right) {
            val right = left + minOf(extent, bounds.right - left)
            val tile = IntRect(left, top, right, bottom)
            val selected = selectTileCommands(commands, tile)
            if (selected.isNotEmpty()) {
                result.add(
                    FabricMinecraftFrameLayer.Portable(
                        selected,
                        tile,
                        ineligibleCount,
                        absoluteCoordinates = true,
                        capacityCount,
                        tintCount,
                        cutoffCount,
                        orderingGroup,
                    ),
                )
                ineligibleCount = 0
                capacityCount = 0
                tintCount = 0
                cutoffCount = 0
            }
            left = right
        }
        top = bottom
    }
    return result
}

// Emit a clip only when it contains a selected primitive, so unrelated clip changes do not invalidate every tile.
@Suppress("CyclomaticComplexMethod")
private fun selectTileCommands(
    commands: List<DrawCommand>,
    tile: IntRect,
): List<DrawCommand> {
    val result = ArrayList<DrawCommand>()
    val clips = ArrayList<DrawCommand>()
    val coverage = arrayListOf(tile)
    var emittedDepth = 0
    commands.forEach { command ->
        when (command) {
            is DrawCommand.PushClip -> {
                clips.add(command)
                coverage.add(intersectTileBounds(coverage.last(), command.bounds))
            }

            is DrawCommand.PushFractionalClip -> {
                clips.add(command)
                coverage.add(encloseTileBounds(command.bounds, coverage.last()))
            }

            DrawCommand.PopClip -> {
                require(clips.isNotEmpty()) { "Clip pop has no matching push." }
                if (emittedDepth == clips.size) {
                    result.add(command)
                    emittedDepth--
                }
                clips.removeAt(clips.lastIndex)
                coverage.removeAt(coverage.lastIndex)
            }

            else -> {
                val clip = coverage.last()
                val visible =
                    when (command) {
                        is DrawCommand.FillRectangle -> intersectTileBounds(command.bounds, clip)
                        is DrawCommand.BlitImage -> intersectTileBounds(command.destination, clip)
                        is DrawCommand.BlitImagePixels -> intersectTileBounds(command.destination, clip)
                        is DrawCommand.SampledImage -> encloseTileBounds(command.destination, clip)
                        else -> error("Portable tiles require portable primitives and balanced clips.")
                    }
                if (0 < visible.width && 0 < visible.height) {
                    while (emittedDepth < clips.size) result.add(clips[emittedDepth++])
                    result.add(command)
                }
            }
        }
    }
    require(clips.isEmpty()) { "Clip push has no matching pop." }
    return result
}

private fun encloseTileBounds(
    bounds: FloatRect,
    clip: IntRect,
): IntRect {
    val left = floor(bounds.left.toDouble().coerceIn(clip.left.toDouble(), clip.right.toDouble())).toInt()
    val top = floor(bounds.top.toDouble().coerceIn(clip.top.toDouble(), clip.bottom.toDouble())).toInt()
    val right = ceil(bounds.right.toDouble().coerceIn(left.toDouble(), clip.right.toDouble())).toInt()
    val bottom = ceil(bounds.bottom.toDouble().coerceIn(top.toDouble(), clip.bottom.toDouble())).toInt()
    return IntRect(left, top, right, bottom)
}

private fun intersectTileBounds(
    first: IntRect,
    second: IntRect,
): IntRect {
    val left = maxOf(first.left, second.left)
    val top = maxOf(first.top, second.top)
    return IntRect(left, top, maxOf(left, minOf(first.right, second.right)), maxOf(top, minOf(first.bottom, second.bottom)))
}
