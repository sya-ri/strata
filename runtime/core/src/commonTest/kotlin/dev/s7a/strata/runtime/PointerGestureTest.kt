package dev.s7a.strata.runtime

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerGesture
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises recognized navigation through the real committed tree on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PointerGestureTest {
    @Test
    fun panAndPinchRespectScaleWithoutSynthesizingActivation() {
        val state = PanZoomState(initialZoom = 2.0)
        var activated = 0
        val source =
            object : TiledImageSource {
                override val bounds = LongRect(0, 0, 100, 100)
                override val levels = listOf(TiledImageLevel(IntSize(16, 16), 1))

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> = StateSource { StateSubscription(StateSnapshot(StateRevision(0), TiledImageTile.Empty)) {} }
            }
        createRuntimeUiSession {
            evaluateComponentTree {
                TiledImage(source, state, IntSize(100, 100), modifier = Modifier.scaleToFit(IntSize(100, 100), allowUpscaling = true).panZoom(state).onPress { activated += 1 })
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(200, 200))
            assertEquals(InputResult.Consumed, session.dispatchPointerGesture(PointerGesture.Pan(IntOffset(50, 50), 20.0, 0.0)))
            assertEquals(45.0, state.metrics.center.x)
            assertEquals(InputResult.Consumed, session.dispatchPointerGesture(PointerGesture.Zoom(IntOffset(100, 100), 2.0)))
            assertEquals(4.0, state.metrics.zoom)
            assertEquals(0, activated)
            assertEquals(InputResult.Ignored, session.dispatchPointerGesture(PointerGesture.Pan(IntOffset(200, 50), 10.0, 0.0)))
        }
    }

    @Test
    fun virtualListSwipesMoveItsViewportWithoutActivatingRows() {
        val state = VirtualListState<Int>()
        var activated = 0
        createRuntimeUiSession {
            evaluateComponentTree {
                VirtualList(100, { it }, { it }, state, IntSize(80, 40), 10) {
                    Spacer(Modifier.size(80, 10).onPress { activated += 1 })
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(80, 40))
            assertEquals(InputResult.Consumed, session.dispatchPointerGesture(PointerGesture.Pan(IntOffset(5, 5), 0.0, -25.0)))
            session.frame(Constraints.fixed(80, 40))
            assertEquals(25.0, state.scrollState.metrics.offset)
            assertEquals(0, activated)
        }
    }

    @Test
    fun invalidNativeNavigationIsRejectedBeforeDispatch() {
        assertFailsWith<IllegalArgumentException> { PointerGesture.Pan(IntOffset.Zero, Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { PointerGesture.Zoom(IntOffset.Zero, 0.0) }
    }
}
