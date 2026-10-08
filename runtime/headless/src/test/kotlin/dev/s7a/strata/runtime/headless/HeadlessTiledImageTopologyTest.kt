@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import kotlin.math.floor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks repeated current-range pans and zooms against independent content-coordinate ARGB sampling.
 */
internal class HeadlessTiledImageTopologyTest {
    @Test
    fun reusedGridKeepsFreshPlacementCoarseFallbackAndStraightArgbAtEveryDensity() {
        for (density in 1..4) {
            val source = Source()
            val navigation = PanZoomState(initialCenter = DoubleOffset(0.25, 0.25), initialZoom = 2.0)
            val size = IntSize(8, 8)
            val session =
                createRuntimeUiSession {
                    evaluateComponentTree {
                        TiledImage(source, navigation, size, cachePolicy = TiledImageCachePolicy(128, 4_096, 1)) {
                            Spacer(
                                Modifier.Empty
                                    .size(1, 1)
                                    .background(ArgbColor(0xFFAA7733.toInt()))
                                    .atContentPosition(DoubleOffset(0.25, 0.25), Alignment.TopStart),
                            )
                        }
                    }
                }
            session.attach()
            val first = session.frame(Constraints.fixed(8, 8))
            val original = rasterizeHeadless(first.drawCommands, size, density)
            val originalPixels = List(8 * density * 8 * density) { index -> original.argbAt(index % (8 * density), index / (8 * density)) }
            val opens = source.opens
            repeat(32) { step ->
                navigation.centerOn(DoubleOffset(if (step % 2 == 0) 0.375 else 0.25, 0.25))
                navigation.zoomTo(if (step % 3 == 0) 2.01 else 2.0)
                val frame = session.frame(Constraints.fixed(8, 8))
                val image = rasterizeHeadless(frame.drawCommands, size, density)
                assertPixels(source, navigation, image, density)
                assertEquals(opens, source.opens)
            }
            session.close()
            assertEquals(source.opens, source.closes)
            assertEquals(originalPixels, List(originalPixels.size) { index -> original.argbAt(index % (8 * density), index / (8 * density)) })
            assertTrue(first.drawCommands.isNotEmpty())
        }
    }

    private fun assertPixels(
        source: Source,
        navigation: PanZoomState,
        image: HeadlessImage,
        density: Int,
    ) {
        val metrics = navigation.metrics
        val markerLeft = floor((0.25 - metrics.center.x) * metrics.scale + 4.0 + 0.5).toInt()
        val markerTop = floor((0.25 - metrics.center.y) * metrics.scale + 4.0 + 0.5).toInt()
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) {
                val contentX = metrics.center.x + ((x + 0.5) / density - 4.0) / metrics.scale
                val contentY = metrics.center.y + ((y + 0.5) / density - 4.0) / metrics.scale
                var color = 0
                for (level in source.levels.indices.reversed()) color = blend(source.sample(level, contentX, contentY), color)
                if (
                    markerLeft * density <= x && x < (markerLeft + 1) * density &&
                    markerTop * density <= y && y < (markerTop + 1) * density
                ) {
                    color = 0xFFAA7733.toInt()
                }
                assertEquals(color, image.argbAt(x, y))
            }
        }
    }

    private fun blend(
        source: Int,
        destination: Int,
    ): Int {
        val alpha = source ushr 24
        if (alpha == 0) return destination
        if (alpha == 255) return source
        val destinationAlpha = destination ushr 24
        if (destinationAlpha == 0) return source
        val remaining = 255 - alpha
        val denominator = alpha * 255 + destinationAlpha * remaining
        fun channel(shift: Int): Int {
            val foreground = source ushr shift and 255
            val background = destination ushr shift and 255
            return (foreground * alpha * 255 + background * destinationAlpha * remaining + denominator / 2) / denominator
        }
        val outputAlpha = (denominator + 127) / 255
        return (outputAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private class Source : TiledImageSource {
        override val bounds: LongRect = LongRect(-8, -8, 8, 8)
        override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(2, 2), 1), TiledImageLevel(IntSize(2, 2), 2))
        var opens = 0
        var closes = 0

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
            opens += 1
            val tile =
                if (empty(id)) {
                    TiledImageTile.Empty
                } else {
                    TiledImageTile.Ready(createDrawImage(IntSize(2, 2), IntArray(4) { index -> color(id, index % 2, index / 2) }))
                }
            return StateSource { StateSubscription(StateSnapshot(StateRevision(0), tile)) { closes += 1 } }
        }

        fun sample(
            level: Int,
            x: Double,
            y: Double,
        ): Int {
            val units = levels[level].contentUnitsPerPixel
            val extent = units * 2L
            val column = floor(x / extent).toLong()
            val row = floor(y / extent).toLong()
            val id = TiledImageTileId(level, column, row)
            if (empty(id)) return 0
            val pixelX = floor((x - column * extent) / units).toInt()
            val pixelY = floor((y - row * extent) / units).toInt()
            return color(id, pixelX, pixelY)
        }

        private fun empty(id: TiledImageTileId): Boolean = id.level == 0 && (id.column + id.row).mod(3L) == 0L

        private fun color(
            id: TiledImageTileId,
            x: Int,
            y: Int,
        ): Int {
            val alpha = if ((x + y).mod(3) == 0) 0 else if (id.level == 0) 127 else 255
            return (alpha shl 24) or (((id.column.toInt() * 17 + x * 73) and 255) shl 16) or (((id.row.toInt() * 29 + y * 61) and 255) shl 8) or ((id.level * 99 + x * 11 + y * 7) and 255)
        }
    }
}
