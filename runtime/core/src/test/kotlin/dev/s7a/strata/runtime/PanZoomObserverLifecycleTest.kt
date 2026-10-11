@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.PanZoomFit
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
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Proves retained viewport suspension, terminal close and failure release the state's derived callback membership.
 */
internal class PanZoomObserverLifecycleTest {
    @Test
    fun detachReleasesThePlanAndGeometryOwnerAndReattachUsesOneFreshObserver() {
        val fixture = Fixture()
        val state = fixture.state
        val session = fixture.session
        session.attach()
        session.frame(CONSTRAINTS)
        state.zoomTo(2.0)
        val first = snapshot(state)
        assertEquals(2, checkNotNull(first).size)
        session.frame(CONSTRAINTS)
        assertSame(first, snapshot(state))

        session.detach()
        assertNull(snapshot(state))
        assertEquals(0, fixture.activeSubscriptions)
        val replacement = state.observe { _ -> }
        state.updateGeometry(BOUNDS, SIZE, PanZoomFit.Cover, replacement)
        replacement.close()
        assertNull(snapshot(state))

        session.attach()
        state.zoomTo(3.0)
        assertEquals(2, checkNotNull(snapshot(state)).size)
        session.frame(CONSTRAINTS)
        assertEquals(1, fixture.activeSubscriptions)
        session.close()
        session.close()
        assertNull(snapshot(state))
        assertEquals(0, fixture.activeSubscriptions)
        assertEquals(3.0, state.metrics.zoom)
    }

    @Test
    fun frameFailureClearsCachedCallbacksBeforeTheReachableStateAndSessionAreClosed() {
        val fixture = Fixture()
        val primary = IllegalArgumentException("tile admission failure")
        fixture.tileFailure = primary
        fixture.session.attach()
        fixture.state.zoomTo(2.0)
        assertEquals(2, checkNotNull(snapshot(fixture.state)).size)

        val failure = assertThrows(IllegalArgumentException::class.java) { fixture.session.frame(CONSTRAINTS) }

        assertSame(primary, failure)
        assertNull(snapshot(fixture.state))
        assertEquals(0, fixture.activeSubscriptions)
        val replacement = fixture.state.observe { _ -> }
        fixture.state.updateGeometry(BOUNDS, SIZE, PanZoomFit.Cover, replacement)
        replacement.close()
        fixture.session.close()
        fixture.session.close()
        assertNull(snapshot(fixture.state))
        assertEquals(2.0, fixture.state.metrics.zoom)
    }

    private fun snapshot(state: PanZoomState): List<*>? {
        val field = PanZoomState::class.java.getDeclaredField("observerSnapshot")
        field.isAccessible = true
        return field.get(state) as List<*>?
    }

    /**
     * Supplies one immutable tile and scalar ownership counters without retaining callbacks.
     */
    private class Fixture : TiledImageSource {
        override val bounds: LongRect = BOUNDS
        override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(16, 16), 64L))
        val state = PanZoomState()
        private val image = createDrawImage(IntSize(16, 16), IntArray(256) { 0xFF336699.toInt() })
        var activeSubscriptions = 0
        var tileFailure: Throwable? = null
        val session: RuntimeUiSession =
            createRuntimeUiSession {
                evaluateComponentTree {
                    TiledImage(
                        this@Fixture,
                        state,
                        SIZE,
                        fit = PanZoomFit.Cover,
                        cachePolicy = TiledImageCachePolicy(maxEntries = 1, maxBytes = 1024L, overscanTiles = 0),
                    )
                }
            }

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
            tileFailure?.let { throw it }
            check(id == TiledImageTileId(0, 0L, 0L))
            return StateSource {
                activeSubscriptions += 1
                StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(image))) { activeSubscriptions -= 1 }
            }
        }
    }

    private companion object {
        val BOUNDS: LongRect = LongRect(0L, 0L, 1024L, 1024L)
        val SIZE: IntSize = IntSize(64, 64)
        val CONSTRAINTS: Constraints = Constraints.fixed(64, 64)
    }
}
