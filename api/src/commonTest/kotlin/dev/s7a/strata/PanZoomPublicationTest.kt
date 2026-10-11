@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomMetrics
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.PanZoomStateObserver
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Freezes ordered publication, mutation, origin and failure semantics on JVM and JavaScript.
 */
internal class PanZoomPublicationTest {
    @Test
    fun stableMembershipReceivesFreshCommittedMetricsAndEqualValuesDoNotNotify() {
        val state = PanZoomState()
        val order = ArrayList<Int>()
        val values = ArrayList<PanZoomMetrics>()
        val handles =
            List(16) { index ->
                state.observe { next ->
                    assertSame(next, state.metrics)
                    order += index
                    values += next
                }
            }

        for (zoom in listOf(2.0, 3.0, 2.0)) {
            val previous = state.metrics
            state.zoomTo(zoom)
            assertEquals((0 until 16).toList(), order)
            assertEquals(List(16) { state.metrics }, values)
            assertEquals(zoom, state.metrics.zoom)
            assertEquals(false, previous === state.metrics)
            order.clear()
            values.clear()
            state.zoomTo(zoom)
            assertEquals(emptyList(), order)
        }
        handles.forEach(PanZoomStateObserver::close)
        state.zoomTo(4.0)
        assertEquals(emptyList(), order)
    }

    @Test
    fun selfReleaseAndRemovalOfALaterObserverKeepTheInvocationOrder() {
        val state = PanZoomState()
        val order = ArrayList<Int>()
        var first: PanZoomStateObserver? = null
        var later: PanZoomStateObserver? = null
        first =
            state.observe {
                first?.close()
                later?.close()
                order += 1
            }
        later = state.observe { order += 2 }
        val last = state.observe { order += 3 }

        state.zoomTo(2.0)
        assertEquals(listOf(1, 3), order)
        order.clear()
        state.zoomTo(3.0)
        assertEquals(listOf(3), order)
        checkNotNull(first).close()
        checkNotNull(later).close()
        last.close()
    }

    @Test
    fun callbackAdmissionStartsAtTheNextPublicationAndUsesDistinctTokensForTheSameCallback() {
        val state = PanZoomState()
        val order = ArrayList<Int>()
        var added: PanZoomStateObserver? = null
        val callback: (PanZoomMetrics) -> Unit = { order += 2 }
        val first =
            state.observe {
                order += 1
                if (added == null) added = state.observe(callback)
            }
        val second = state.observe(callback)

        state.zoomTo(2.0)
        assertEquals(listOf(1, 2), order)
        order.clear()
        state.zoomTo(3.0)
        assertEquals(listOf(1, 2, 2), order)
        second.close()
        order.clear()
        state.zoomTo(4.0)
        assertEquals(listOf(1, 2), order)
        first.close()
        added?.close()
    }

    @Test
    fun mutationAndDistinctFailuresStillAttemptLiveObserversAndRecoverOnTheNextPublication() {
        val state = PanZoomState()
        val primary = IllegalArgumentException("primary")
        val secondary = IllegalStateException("secondary")
        val order = ArrayList<Int>()
        var later: PanZoomStateObserver? = null
        var added: PanZoomStateObserver? = null
        val first =
            state.observe {
                order += 1
                later?.close()
                added = state.observe { order += 5 }
                throw primary
            }
        later = state.observe { order += 2 }
        val second =
            state.observe {
                order += 3
                throw primary
            }
        val third =
            state.observe {
                order += 4
                throw secondary
            }
        val last =
            state.observe {
                assertSame(it, state.metrics)
                order += 6
            }

        val failure = assertFailsWith<IllegalArgumentException> { state.zoomTo(2.0) }
        assertSame(primary, failure)
        assertEquals(listOf(secondary), failure.suppressedExceptions)
        assertEquals(listOf(1, 3, 4, 6), order)
        assertEquals(2.0, state.metrics.zoom)
        first.close()
        second.close()
        third.close()
        order.clear()
        state.zoomTo(3.0)
        assertEquals(listOf(6, 5), order)
        last.close()
        added?.close()
    }

    @Test
    fun everyNestedWriteIsRejectedAndLaterObserversSeeTheCommittedSnapshot() {
        val state = PanZoomState()
        var geometry: PanZoomStateObserver? = null
        var rejections = 0
        val first =
            state.observe {
                val writes =
                    listOf<() -> Unit>(
                        { state.zoomTo(3.0) },
                        { state.zoomBy(2.0) },
                        { state.panBy(DoubleOffset(1.0, 1.0)) },
                        { state.centerOn(DoubleOffset.Zero) },
                        { state.reset() },
                        { state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, checkNotNull(geometry)) },
                    )
                writes.forEach { write ->
                    assertFailsWith<IllegalStateException>(block = write)
                    rejections += 1
                }
            }
        var delivered = 0
        geometry =
            state.observe {
                assertSame(it, state.metrics)
                delivered += 1
            }

        state.zoomTo(2.0)
        assertEquals(6, rejections)
        assertEquals(1, delivered)
        first.close()
        state.zoomTo(3.0)
        assertEquals(2, delivered)
        checkNotNull(geometry).close()
    }

    @Test
    fun uncaughtNestedFailureDoesNotStopLaterObserversOrPoisonTheNextPublication() {
        val state = PanZoomState()
        val first = state.observe { state.zoomTo(4.0) }
        var delivered = 0
        val last =
            state.observe {
                assertSame(it, state.metrics)
                delivered += 1
            }

        assertFailsWith<IllegalStateException> { state.zoomTo(2.0) }
        assertEquals(1, delivered)
        assertEquals(2.0, state.metrics.zoom)
        first.close()
        state.zoomTo(3.0)
        assertEquals(2, delivered)
        last.close()
    }

    @Test
    fun geometryFeedbackAndOrdinaryPublicationAlternateWithoutCachingTheOrigin() {
        val state = PanZoomState()
        val order = ArrayList<Int>()
        val first =
            state.observe {
                assertSame(it, state.metrics)
                order += 1
            }
        val second =
            state.observe {
                assertSame(it, state.metrics)
                order += 2
            }

        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Contain, first)
        assertEquals(listOf(2), order)
        order.clear()
        state.zoomTo(2.0)
        assertEquals(listOf(1, 2), order)
        order.clear()
        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, first)
        assertEquals(listOf(2), order)
        first.close()
        order.clear()
        state.updateGeometry(BOUNDS, IntSize(100, 100), PanZoomFit.Contain, second)
        assertEquals(emptyList(), order)
        assertEquals(IntSize(100, 100), state.metrics.viewportSize)
        state.zoomTo(3.0)
        assertEquals(listOf(2), order)
        second.close()
    }

    private companion object {
        val BOUNDS: LongRect = LongRect(0L, 0L, 1000L, 500L)
        val VIEWPORT: IntSize = IntSize(200, 200)
    }
}
