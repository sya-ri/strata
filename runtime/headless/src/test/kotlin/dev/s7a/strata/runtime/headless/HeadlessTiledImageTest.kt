@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies portable tiled-image commands through the public one-shot headless rasterizer.
 */
internal class HeadlessTiledImageTest {
    @Test
    fun tiledImagePreservesSourceDetailAtFinalPixelScaleAndClosesItsVisibleObservation() {
        val image: DrawImage = createDrawImage(IntSize(2, 1), intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        var closes = 0
        val source =
            object : TiledImageSource {
                override val bounds: LongRect = LongRect(0L, 0L, 2L, 1L)
                override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(2, 1), 1L))

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                    require(id == TiledImageTileId(0, 0L, 0L))
                    return StateSource {
                        StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(image))) { closes += 1 }
                    }
                }
            }
        val description =
            evaluateComponentTree {
                TiledImage(
                    source,
                    PanZoomState(),
                    IntSize(4, 2),
                    cachePolicy = TiledImageCachePolicy(maxEntries = 1, maxBytes = 8L, overscanTiles = 0),
                )
            }

        val frame = renderHeadless(description, IntSize(4, 2), scale = 2)

        assertEquals(IntSize(8, 4), frame.image.size)
        for (y in 0 until 4) {
            for (x in 0 until 4) assertEquals(0xFFFF0000.toInt(), frame.image.argbAt(x, y))
            for (x in 4 until 8) assertEquals(0xFF00FF00.toInt(), frame.image.argbAt(x, y))
        }
        assertEquals(1, closes)
    }

    @Test
    fun adjacentUnitTilesRemainDistinctAtTheDoubleIntegerPrecisionBoundary() {
        val firstColumn = 9_007_199_254_740_992L
        val colors =
            listOf(
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
            )
        val tiles =
            colors
                .mapIndexed { index, color ->
                    Math.addExact(firstColumn, index.toLong()) to createDrawImage(IntSize(1, 1), intArrayOf(color))
                }.toMap()
        val source =
            object : TiledImageSource {
                override val bounds: LongRect = LongRect(firstColumn, 0L, Math.addExact(firstColumn, colors.size.toLong()), 1L)
                override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(1, 1), 1L))

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                    require(id.level == 0 && id.row == 0L)
                    val image = requireNotNull(tiles[id.column])
                    return StateSource {
                        StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(image))) {}
                    }
                }
            }
        val description =
            evaluateComponentTree {
                TiledImage(
                    source,
                    PanZoomState(),
                    IntSize(colors.size, 1),
                    cachePolicy = TiledImageCachePolicy(maxEntries = colors.size, maxBytes = colors.size * 4L, overscanTiles = 0),
                )
            }

        val frame = renderHeadless(description, IntSize(colors.size, 1))

        assertEquals(colors, colors.indices.map { x -> frame.image.argbAt(x, 0) })
    }

    @Test
    fun subUlpViewportsAtLargeTileBoundariesRemainVisible() {
        assertLargeBoundaryTiles(
            bounds = LongRect(9_007_199_254_740_992L, 0L, 9_007_199_254_741_008L, 8L),
            center = 9_007_199_254_741_000L,
            tileExtent = 8L,
        )
        assertLargeBoundaryTiles(
            bounds = LongRect(-9_007_199_254_741_008L, 0L, -9_007_199_254_740_992L, 8L),
            center = -9_007_199_254_741_000L,
            tileExtent = 8L,
        )
        assertLargeBoundaryTiles(
            bounds = LongRect(9_007_199_254_740_984L, 0L, 9_007_199_254_741_008L, 6L),
            center = 9_007_199_254_740_996L,
            tileExtent = 6L,
        )
    }

    @Test
    fun overlaysFarOutsideBothViewportEdgesRemainClippedWithoutOverflowingLayout() {
        val bounds = LongRect(0L, 0L, 10_000_000_000L, 1L)
        val source = emptySource(bounds)
        val description =
            evaluateComponentTree {
                TiledImage(
                    source,
                    PanZoomState(),
                    IntSize(1, 1),
                    fit = PanZoomFit.Cover,
                    cachePolicy = TiledImageCachePolicy(maxEntries = 2, maxBytes = 8L, overscanTiles = 0),
                ) {
                    Spacer(
                        Modifier.Empty
                            .size(1, 1)
                            .background(ArgbColor(0xFFFF0000.toInt()))
                            .atContentPosition(DoubleOffset(1.0, 0.5)),
                    )
                    Spacer(
                        Modifier.Empty
                            .size(1, 1)
                            .background(ArgbColor(0xFF00FF00.toInt()))
                            .atContentPosition(DoubleOffset(bounds.right.toDouble() - 1.0, 0.5)),
                    )
                }
            }

        val frame = renderHeadless(description, IntSize(1, 1))

        assertEquals(0, frame.image.argbAt(0, 0))
    }

    @Test
    fun edgeTilesOutsideNarrowBoundsRenderWhenTheirCenterDeltaExceedsLongRange() {
        val bounds = LongRect(-100L, 0L, 1L, 1L)
        val transparent = createDrawImage(IntSize(1, 1), intArrayOf(0))
        val source =
            object : TiledImageSource {
                override val bounds: LongRect = bounds
                override val levels: List<TiledImageLevel> =
                    listOf(TiledImageLevel(IntSize(1, 1), Long.MAX_VALUE))

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
                    StateSource {
                        StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(transparent))) {}
                    }
            }
        val description =
            evaluateComponentTree {
                TiledImage(
                    source,
                    PanZoomState(),
                    IntSize(1, 1),
                    cachePolicy = TiledImageCachePolicy(maxEntries = 2, maxBytes = 8L, overscanTiles = 0),
                )
            }

        val frame = renderHeadless(description, IntSize(1, 1))

        assertEquals(0, frame.image.argbAt(0, 0))
    }

    private fun assertLargeBoundaryTiles(
        bounds: LongRect,
        center: Long,
        tileExtent: Long,
    ) {
        val leftColumn = Math.decrementExact(Math.floorDiv(center, tileExtent))
        val rightColumn = Math.incrementExact(leftColumn)
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val images =
            mapOf(
                leftColumn to createDrawImage(IntSize(1, 1), intArrayOf(red)),
                rightColumn to createDrawImage(IntSize(1, 1), intArrayOf(green)),
            )
        val requests = ArrayList<TiledImageTileId>()
        val source =
            object : TiledImageSource {
                override val bounds: LongRect = bounds
                override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(1, 1), tileExtent))

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                    requests.add(id)
                    require(id.level == 0 && id.row == 0L)
                    val image = requireNotNull(images[id.column])
                    return StateSource {
                        StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(image))) {}
                    }
                }
            }
        val description =
            evaluateComponentTree {
                TiledImage(
                    source = source,
                    state =
                        PanZoomState(
                            initialCenter = DoubleOffset(center.toDouble(), tileExtent.toDouble() / 2.0),
                            initialZoom = 64.0,
                            maximumZoom = 64.0,
                        ),
                    size = IntSize(8, 8),
                    cachePolicy = TiledImageCachePolicy(maxEntries = 2, maxBytes = 8L, overscanTiles = 0),
                )
            }

        val frame = renderHeadless(description, IntSize(8, 8))

        assertEquals(setOf(TiledImageTileId(0, leftColumn, 0L), TiledImageTileId(0, rightColumn, 0L)), requests.toSet())
        for (y in 0 until 8) {
            for (x in 0 until 4) assertEquals(red, frame.image.argbAt(x, y))
            for (x in 4 until 8) assertEquals(green, frame.image.argbAt(x, y))
        }
    }

    private fun emptySource(bounds: LongRect): TiledImageSource =
        object : TiledImageSource {
            override val bounds: LongRect = bounds
            override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(1, 1), 1L))

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
                StateSource {
                    StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Empty)) {}
                }
        }
}
