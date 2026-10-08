@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.PanZoomMetrics
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Independent finite-grid reference: scans source coordinates instead of the retained planner's binary searches.
 * Exact decimal subtraction preserves nearby integer coordinates at the Double integer-precision boundary.
 */
internal object TiledImageTopologyOracle {
    /**
     * Selects the finest admitted preferred-or-coarser grid using exact entry and RGBA reservation totals.
     */
    fun plan(
        source: TiledImageSource,
        metrics: PanZoomMetrics,
        size: IntSize,
        policy: TiledImageCachePolicy,
    ): Plan {
        val preferred = source.levels.indexOfLast { level -> metrics.scale * level.contentUnitsPerPixel <= 1.0 }.coerceAtLeast(0)
        for (selected in preferred until source.levels.size) {
            val visible = source.levels.indices.associateWith { level -> visible(source, level, metrics, size) }
            val required =
                (selected until source.levels.size).flatMap { level ->
                    val margin = if (level == selected) policy.overscanTiles else 0
                    expanded(source, level, visible.getValue(level), margin)
                }
            val bytes =
                required.fold(BigInteger.ZERO) { total, id ->
                    val pixels = source.levels[id.level].tilePixelSize
                    total + BigInteger.valueOf(pixels.width.toLong() * pixels.height * 4L)
                }
            if (required.size <= policy.maxEntries && bytes <= BigInteger.valueOf(policy.maxBytes)) {
                val painted = (source.levels.lastIndex downTo selected).flatMap { level -> visible.getValue(level) }
                return Plan(selected, required, painted)
            }
        }
        error("No reference grid is admitted.")
    }

    /**
     * Computes one original full tile rectangle independently of the retained cell objects.
     */
    fun destination(
        source: TiledImageSource,
        id: TiledImageTileId,
        metrics: PanZoomMetrics,
        size: IntSize,
    ): FloatRect {
        val level = source.levels[id.level]
        val width = level.tilePixelSize.width * level.contentUnitsPerPixel
        val height = level.tilePixelSize.height * level.contentUnitsPerPixel
        val left = Math.multiplyExact(id.column, width)
        val top = Math.multiplyExact(id.row, height)
        fun horizontal(value: Long): Float = ((BigDecimal.valueOf(value) - BigDecimal(metrics.center.x)).toDouble() * metrics.scale + size.width / 2.0).toFloat()
        fun vertical(value: Long): Float = ((BigDecimal.valueOf(value) - BigDecimal(metrics.center.y)).toDouble() * metrics.scale + size.height / 2.0).toFloat()
        return FloatRect(horizontal(left), vertical(top), horizontal(Math.addExact(left, width)), vertical(Math.addExact(top, height)))
    }

    private fun visible(
        source: TiledImageSource,
        levelIndex: Int,
        metrics: PanZoomMetrics,
        size: IntSize,
    ): List<TiledImageTileId> {
        val level = source.levels[levelIndex]
        val width = level.tilePixelSize.width * level.contentUnitsPerPixel
        val height = level.tilePixelSize.height * level.contentUnitsPerPixel
        val halfWidth = BigDecimal((size.width / metrics.scale / 2.0).coerceAtLeast(Double.MIN_VALUE))
        val halfHeight = BigDecimal((size.height / metrics.scale / 2.0).coerceAtLeast(Double.MIN_VALUE))
        val centerX = BigDecimal(metrics.center.x)
        val centerY = BigDecimal(metrics.center.y)
        return grid(source, levelIndex).filter { id ->
            val left = BigDecimal.valueOf(Math.multiplyExact(id.column, width)) - centerX
            val top = BigDecimal.valueOf(Math.multiplyExact(id.row, height)) - centerY
            -halfWidth < left + BigDecimal.valueOf(width) &&
                left < halfWidth &&
                -halfHeight < top + BigDecimal.valueOf(height) &&
                top < halfHeight
        }
    }

    private fun expanded(
        source: TiledImageSource,
        levelIndex: Int,
        visible: List<TiledImageTileId>,
        margin: Int,
    ): List<TiledImageTileId> {
        if (visible.isEmpty() || margin == 0) return visible
        val left = visible.minOf(TiledImageTileId::column) - margin
        val right = visible.maxOf(TiledImageTileId::column) + margin
        val top = visible.minOf(TiledImageTileId::row) - margin
        val bottom = visible.maxOf(TiledImageTileId::row) + margin
        return grid(source, levelIndex).filter { id -> left <= id.column && id.column <= right && top <= id.row && id.row <= bottom }
    }

    private fun grid(
        source: TiledImageSource,
        levelIndex: Int,
    ): List<TiledImageTileId> {
        val level = source.levels[levelIndex]
        val width = level.tilePixelSize.width * level.contentUnitsPerPixel
        val height = level.tilePixelSize.height * level.contentUnitsPerPixel
        val firstColumn = Math.floorDiv(source.bounds.left, width)
        val lastColumn = Math.floorDiv(source.bounds.right - 1L, width)
        val firstRow = Math.floorDiv(source.bounds.top, height)
        val lastRow = Math.floorDiv(source.bounds.bottom - 1L, height)
        return buildList {
            for (row in firstRow..lastRow) {
                for (column in firstColumn..lastColumn) add(TiledImageTileId(levelIndex, column, row))
            }
        }
    }

    /**
     * Detached scalar reference output; paint order is coarse-to-fine and row-major within each level.
     */
    data class Plan(
        val selectedLevel: Int,
        val required: List<TiledImageTileId>,
        val painted: List<TiledImageTileId>,
    )
}
