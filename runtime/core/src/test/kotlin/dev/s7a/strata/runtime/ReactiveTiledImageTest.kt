package dev.s7a.strata.runtime

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

/**
 * Verifies direct descriptor replacement without replacing the tile viewport or caller-owned navigation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ReactiveTiledImageTest {
    @Test
    fun replacementKeepsNavigationAndClosesOnlyPreviousTileObservers() {
        val first = ObserveTestSource<TiledImageTile>(TiledImageTile.Ready(createDrawImage(IntSize(8, 8), IntArray(64) { 1 })))
        val second = ObserveTestSource<TiledImageTile>(TiledImageTile.Ready(createDrawImage(IntSize(8, 8), IntArray(64) { 2 })))
        val descriptor = ObserveTestSource(source(first))
        val navigation = PanZoomState(initialZoom = 2.0)
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    TiledImage(descriptor, navigation, IntSize(8, 8), cachePolicy = TiledImageCachePolicy(maxEntries = 2, overscanTiles = 0))
                }
            }
        session.use {
            session.attach()
            val before = session.frame(Constraints.fixed(8, 8))
            val position = navigation.metrics
            session.startRenderMonitoring().use { monitor ->
                descriptor.publish(source(second))
                val after = session.frame(Constraints.fixed(8, 8))
                assertNotSame(
                    before.drawCommands
                        .filterIsInstance<DrawCommand.SampledImage>()
                        .single()
                        .image,
                    after.drawCommands
                        .filterIsInstance<DrawCommand.SampledImage>()
                        .single()
                        .image,
                )
                assertEquals(position, navigation.metrics)
                assertEquals(1, first.releases)
                assertEquals(1, second.subscriptions)
                assertEquals(0, second.releases)
                assertEquals(1L, monitor.snapshot().counts[UiRenderMetric.StateComponentEvaluation])
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.NodeDispose])
            }
        }
        assertEquals(1, second.releases)
        assertEquals(1, descriptor.releases)
    }

    private fun source(frames: StateSource<TiledImageTile>): TiledImageSource =
        object : TiledImageSource {
            override val bounds: LongRect = LongRect(0, 0, 8, 8)
            override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(8, 8), 1L))

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                require(id == TiledImageTileId(0, 0, 0))
                return frames
            }
        }
}
