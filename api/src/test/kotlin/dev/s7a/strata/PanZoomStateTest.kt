@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomMetrics
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies owner-thread transforms, fitting, clamping, and observation for [PanZoomState].
 */
internal class PanZoomStateTest {
    @Test
    fun geometryResolvesContainAndCoverScalesAndClampsEachAxis() {
        val contain = PanZoomState(initialCenter = DoubleOffset(-100.0, -100.0))
        val containObserver = contain.observe { _ -> }
        contain.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Contain, containObserver)

        assertMetrics(contain.metrics, center = DoubleOffset(500.0, 250.0), scale = 0.2, fit = PanZoomFit.Contain)

        val cover = PanZoomState(initialCenter = DoubleOffset(-100.0, -100.0))
        val coverObserver = cover.observe { _ -> }
        cover.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, coverObserver)

        assertMetrics(cover.metrics, center = DoubleOffset(250.0, 250.0), scale = 0.4, fit = PanZoomFit.Cover)

        containObserver.close()
        coverObserver.close()
    }

    @Test
    fun anchoredZoomPreservesContentCoordinateUntilBoundsClampingIsRequired() {
        val state = PanZoomState()
        val observer = state.observe { _ -> }
        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, observer)
        val anchor = DoubleOffset(150.0, 100.0)
        val before = state.localToContent(anchor)

        assertEquals(2.0, state.zoomTo(2.0, anchor), EPSILON)

        assertOffset(before, state.localToContent(anchor))
        assertOffset(anchor, state.contentToLocal(before))
        assertOffset(DoubleOffset(562.5, 250.0), state.metrics.center)
        observer.close()
    }

    @Test
    fun panningAndZoomLimitsRetainOnlyClampedFiniteState() {
        val state = PanZoomState(initialZoom = 2.0, minimumZoom = 0.5, maximumZoom = 4.0)
        val observer = state.observe { _ -> }
        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, observer)

        assertOffset(DoubleOffset(125.0, 125.0), state.panBy(DoubleOffset(-1000.0, -1000.0)))
        assertEquals(4.0, state.zoomBy(100.0), EPSILON)
        assertEquals(0.5, state.zoomBy(0.0001), EPSILON)
        assertThrows(IllegalArgumentException::class.java) { state.zoomBy(0.0) }
        assertThrows(IllegalArgumentException::class.java) { state.zoomTo(Double.NaN) }
        val unattached = PanZoomState(initialCenter = DoubleOffset(Double.MAX_VALUE, 0.0))
        assertThrows(IllegalArgumentException::class.java) { unattached.panBy(DoubleOffset(Double.MAX_VALUE, 0.0)) }

        observer.close()
    }

    @Test
    fun resetRecentersKnownGeometryAndDefersUnknownGeometryCentering() {
        val state = PanZoomState(initialCenter = DoubleOffset(12.0, 34.0), initialZoom = 3.0)

        val unknown = state.reset()
        assertFalse(unknown.geometryKnown)
        assertOffset(DoubleOffset.Zero, unknown.center)
        assertEquals(1.0, unknown.zoom, EPSILON)

        val observer = state.observe { _ -> }
        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, observer)
        state.centerOn(DoubleOffset(700.0, 250.0))
        state.zoomTo(4.0)

        val known = state.reset()
        assertOffset(DoubleOffset(500.0, 250.0), known.center)
        assertEquals(1.0, known.zoom, EPSILON)
        observer.close()
    }

    @Test
    fun resetFailureRetainsMetricsAndTheExplicitCenterRequest() {
        val state =
            PanZoomState(
                initialCenter = DoubleOffset(700.0, 250.0),
                minimumZoom = Double.MIN_VALUE,
            )
        val observer = state.observe { _ -> }
        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, observer)
        val before = state.metrics

        assertThrows(IllegalArgumentException::class.java) { state.reset() }

        assertEquals(before, state.metrics)
        state.updateGeometry(LongRect(0L, 0L, 1200L, 500L), VIEWPORT, PanZoomFit.Cover, observer)
        assertOffset(DoubleOffset(700.0, 250.0), state.metrics.center)
        observer.close()
    }

    @Test
    fun geometryOwnerSuppressesFeedbackAndMustReleaseBeforeAnotherViewportPublishes() {
        val state = PanZoomState()
        val firstChanges = ArrayList<PanZoomMetrics>()
        val secondChanges = ArrayList<PanZoomMetrics>()
        val first = state.observe(firstChanges::add)
        val second = state.observe(secondChanges::add)

        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Contain, first)
        assertTrue(firstChanges.isEmpty())
        assertEquals(1, secondChanges.size)
        assertThrows(IllegalStateException::class.java) {
            state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, second)
        }

        state.zoomTo(2.0)
        assertEquals(1, firstChanges.size)
        assertEquals(2, secondChanges.size)

        first.close()
        first.close()
        state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, second)
        assertEquals(PanZoomFit.Cover, state.metrics.fit)
        second.close()
        assertThrows(IllegalStateException::class.java) {
            state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, second)
        }
    }

    @Test
    fun observerCannotPublishAStaleNestedTransformToLaterObservers() {
        val state = PanZoomState(maximumZoom = 4.0)
        val writeFailure = AtomicReference<Throwable?>()
        val observed = ArrayList<Pair<Double, Double>>()
        val first =
            state.observe { metrics ->
                if (metrics.zoom == 2.0) {
                    writeFailure.set(runCatching { state.zoomTo(3.0) }.exceptionOrNull())
                }
            }
        val second = state.observe { metrics -> observed += metrics.zoom to state.metrics.zoom }

        state.zoomTo(2.0)

        assertTrue(writeFailure.get() is IllegalStateException)
        assertEquals(listOf(2.0 to 2.0), observed)
        assertEquals(2.0, state.metrics.zoom, EPSILON)
        first.close()
        second.close()
    }

    @Test
    fun observerFailureStillNotifiesLaterObserversAndPreservesTheCommittedMetrics() {
        val state = PanZoomState(maximumZoom = 4.0)
        val primary = IllegalArgumentException("first observer")
        val secondary = IllegalStateException("second observer")
        val observed = ArrayList<Double>()
        val first = state.observe { throw primary }
        val second =
            state.observe { metrics ->
                observed += metrics.zoom
                throw secondary
            }
        val third = state.observe { metrics -> observed += metrics.zoom }

        val failure = assertThrows(IllegalArgumentException::class.java) { state.zoomTo(2.0) }

        assertTrue(failure === primary)
        assertEquals(listOf(secondary), failure.suppressed.toList())
        assertEquals(listOf(2.0, 2.0), observed)
        assertEquals(2.0, state.metrics.zoom, EPSILON)
        first.close()
        second.close()
        third.close()
    }

    @Test
    fun invalidConstructionAndGeometryFailWithoutReplacingMetrics() {
        assertThrows(IllegalArgumentException::class.java) { PanZoomState(initialZoom = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { PanZoomState(minimumZoom = 2.0) }
        assertThrows(IllegalArgumentException::class.java) { PanZoomState(maximumZoom = Double.POSITIVE_INFINITY) }
        val state = PanZoomState()
        val observer = state.observe { _ -> }
        val initial = state.metrics

        assertThrows(IllegalArgumentException::class.java) {
            state.updateGeometry(LongRect.Zero, VIEWPORT, PanZoomFit.Contain, observer)
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.updateGeometry(BOUNDS, IntSize.Zero, PanZoomFit.Contain, observer)
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.updateGeometry(
                LongRect(Long.MAX_VALUE - 1L, 0L, Long.MAX_VALUE, 1L),
                VIEWPORT,
                PanZoomFit.Contain,
                observer,
            )
        }
        assertEquals(initial, state.metrics)
        observer.close()
    }

    @Test
    fun stateAndObserverReleaseRejectAnotherThread() {
        val state = PanZoomState()
        val observer = state.observe { _ -> }
        val readFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val worker =
            Thread {
                readFailure.set(runCatching { state.metrics }.exceptionOrNull())
                closeFailure.set(runCatching { observer.close() }.exceptionOrNull())
            }

        worker.start()
        worker.join()

        assertTrue(readFailure.get() is IllegalStateException)
        assertTrue(closeFailure.get() is IllegalStateException)
        observer.close()
    }

    @Test
    fun geometryValuesValidateLargeRectanglesAndFiniteOffsets() {
        assertThrows(IllegalArgumentException::class.java) { DoubleOffset(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { DoubleOffset.Zero + DoubleOffset(Double.MAX_VALUE, Double.MAX_VALUE) + DoubleOffset(Double.MAX_VALUE, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { LongRect(1L, 0L, 0L, 1L) }
        assertThrows(ArithmeticException::class.java) { LongRect(Long.MIN_VALUE, 0L, Long.MAX_VALUE, 1L) }
        val bounds = LongRect(-10L, -20L, 30L, 40L)
        assertEquals(40L, bounds.width)
        assertEquals(60L, bounds.height)
        assertTrue(DoubleOffset(-10.0, -20.0) in bounds)
        assertFalse(DoubleOffset(30.0, 40.0) in bounds)

        val exactDoubleInteger = 9_007_199_254_740_992L
        val precisionBoundary = LongRect(exactDoubleInteger, 0L, Math.incrementExact(exactDoubleInteger), 1L)
        assertTrue(DoubleOffset(exactDoubleInteger.toDouble(), 0.0) in precisionBoundary)
        assertFalse(DoubleOffset(Math.addExact(exactDoubleInteger, 2L).toDouble(), 0.0) in precisionBoundary)
        assertTrue(DoubleOffset(Long.MIN_VALUE.toDouble(), 0.0) in LongRect(Long.MIN_VALUE, 0L, Long.MIN_VALUE + 1L, 1L))
        assertFalse(DoubleOffset(Long.MAX_VALUE.toDouble(), 0.0) in LongRect(Long.MAX_VALUE - 1L, 0L, Long.MAX_VALUE, 1L))
    }

    private fun assertMetrics(
        actual: PanZoomMetrics,
        center: DoubleOffset,
        scale: Double,
        fit: PanZoomFit,
    ) {
        assertTrue(actual.geometryKnown)
        assertOffset(center, actual.center)
        assertEquals(scale, actual.scale, EPSILON)
        assertEquals(fit, actual.fit)
        assertEquals(BOUNDS, actual.contentBounds)
        assertEquals(VIEWPORT, actual.viewportSize)
    }

    private fun assertOffset(
        expected: DoubleOffset,
        actual: DoubleOffset,
    ) {
        assertEquals(expected.x, actual.x, EPSILON)
        assertEquals(expected.y, actual.y, EPSILON)
    }

    private companion object {
        const val EPSILON: Double = 0.000000001
        val BOUNDS: LongRect = LongRect(0L, 0L, 1000L, 500L)
        val VIEWPORT: IntSize = IntSize(200, 200)
    }
}
