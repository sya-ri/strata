@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
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
@Suppress("LongParameterList") // Admission reasons belong to original occurrences, not duplicated tile commands.
internal fun tileFabricMinecraftPortable(
    commands: List<DrawCommand>,
    bounds: IntRect,
    scale: Int,
    ineligibleSampledImages: Int = 0,
    capacitySampledImages: Int = 0,
    tintFallbackImages: Int = 0,
    alphaCutoffFallbackImages: Int = 0,
): List<FabricMinecraftFrameLayer.Portable> {
    require(0 < scale) { "Minecraft GUI scale must be positive." }
    val width = Math.multiplyExact(bounds.width, scale)
    val height = Math.multiplyExact(bounds.height, scale)
    Math.multiplyExact(bounds.right, scale)
    Math.multiplyExact(bounds.bottom, scale)
    if (width.toLong() * height <= 262_144L) {
        val absolute = commands.any { it is DrawCommand.SampledImage }
        val detached = if (absolute) commands.toList() else localizeFabricPortable(commands, bounds)
        return listOf(FabricMinecraftFrameLayer.Portable(detached, bounds, ineligibleSampledImages, absolute, capacitySampledImages, tintFallbackImages, alphaCutoffFallbackImages))
    }
    var extent = maxOf(1, 256 / scale)
    val orderingGroup = Any()

    fun count(): Long = ((bounds.width.toLong() + extent - 1) / extent) * ((bounds.height.toLong() + extent - 1) / extent)
    while (64L < count()) extent = Math.multiplyExact(extent, 2)
    val selectedCommands = partitionTileCommands(commands, bounds, extent, count().toInt())
    var tileIndex = 0
    var ineligibleCount = ineligibleSampledImages
    var capacityCount = capacitySampledImages
    var tintCount = tintFallbackImages
    var cutoffCount = alphaCutoffFallbackImages
    val result = ArrayList<FabricMinecraftFrameLayer.Portable>()
    var top = bounds.top
    while (top < bounds.bottom) {
        val bottom = top + minOf(extent, bounds.bottom - top)
        var left = bounds.left
        while (left < bounds.right) {
            val right = left + minOf(extent, bounds.right - left)
            val tile = IntRect(left, top, right, bottom)
            val selected = selectedCommands[tileIndex++]
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

// Read commands once and emit clips only for selected primitives, so unrelated clip changes still leave other tiles equal.
@Suppress("CyclomaticComplexMethod", "LongMethod") // Keep one clip-stack traversal with the bounded row/column distribution.
private fun partitionTileCommands(
    commands: List<DrawCommand>,
    bounds: IntRect,
    extent: Int,
    tileCount: Int,
): Array<ArrayList<DrawCommand>> {
    val columns = ((bounds.width.toLong() + extent - 1) / extent).toInt()
    val result = Array(tileCount) { ArrayList<DrawCommand>() }
    val clips = ArrayList<DrawCommand>()
    val coverage = arrayListOf(bounds)
    val emittedDepth = IntArray(tileCount)
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
                result.forEachIndexed { index, selected ->
                    if (emittedDepth[index] == clips.size) {
                        selected.add(command)
                        emittedDepth[index]--
                    }
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
                if (visible.width <= 0 || visible.height <= 0) return@forEach
                val firstColumn = ((visible.left.toLong() - bounds.left) / extent).toInt()
                val lastColumn = ((visible.right.toLong() - 1 - bounds.left) / extent).toInt()
                val firstRow = ((visible.top.toLong() - bounds.top) / extent).toInt()
                val lastRow = ((visible.bottom.toLong() - 1 - bounds.top) / extent).toInt()
                for (row in firstRow..lastRow) {
                    for (column in firstColumn..lastColumn) {
                        val index = row * columns + column
                        emittedDepth[index] = emitTileCommand(result[index], clips, emittedDepth[index], command)
                    }
                }
            }
        }
    }
    require(clips.isEmpty()) { "Clip push has no matching pop." }
    return result
}

private fun emitTileCommand(
    selected: ArrayList<DrawCommand>,
    clips: List<DrawCommand>,
    emittedDepth: Int,
    command: DrawCommand,
): Int {
    var depth = emittedDepth
    while (depth < clips.size) selected.add(clips[depth++])
    selected.add(command)
    return depth
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
