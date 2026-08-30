package dev.s7a.strata

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the retained pointer policy installed by [panZoom].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PanZoomModifierTest {
    @Test
    fun primaryPressPansThroughOutsideDragAndMatchingReleaseEndsGesture() {
        val state = attachedState(initialZoom = 2.0)
        val node = pointerNode(Modifier.Empty.panZoom(state))

        assertEquals(InputResult.Ignored, node.onPointerEvent(PointerEvent.Press(IntOffset.Zero, PointerButton.Secondary), IntOffset.Zero))
        assertEquals(InputResult.Consumed, node.onPointerEvent(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero))
        node.onPointerCaptureAcquired(PointerButton.Primary)
        assertEquals(
            InputResult.Consumed,
            node.onPointerEvent(
                PointerEvent.Drag(IntOffset(-200, -300), PointerButton.Primary, deltaX = 20.0, deltaY = -10.0),
                IntOffset(-200, -300),
            ),
        )
        assertOffset(DoubleOffset(400.0, 550.0), state.metrics.center)
        assertEquals(
            InputResult.Consumed,
            node.onPointerEvent(PointerEvent.Release(IntOffset(-200, -300), PointerButton.Primary), IntOffset(-200, -300)),
        )
        assertEquals(
            InputResult.Ignored,
            node.onPointerEvent(
                PointerEvent.Drag(IntOffset.Zero, PointerButton.Primary, deltaX = 20.0, deltaY = 0.0),
                IntOffset.Zero,
            ),
        )
        assertOffset(DoubleOffset(400.0, 550.0), state.metrics.center)
    }

    @Test
    fun cancellationStopsCapturedPanWithoutChangingTransform() {
        val state = attachedState(initialZoom = 2.0)
        val node = pointerNode(Modifier.Empty.panZoom(state))
        node.onPointerEvent(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)
        node.onPointerCaptureAcquired(PointerButton.Primary)
        node.onPointerCaptureCancelled(PointerButton.Primary)

        assertEquals(
            InputResult.Ignored,
            node.onPointerEvent(
                PointerEvent.Drag(IntOffset.Zero, PointerButton.Primary, deltaX = 10.0, deltaY = 10.0),
                IntOffset.Zero,
            ),
        )
        assertOffset(DoubleOffset(500.0, 500.0), state.metrics.center)
    }

    @Test
    fun scrollZoomKeepsPointerAnchorAndConsumesWithoutStartingPan() {
        val state = attachedState(initialZoom = 2.0)
        val node = pointerNode(Modifier.Empty.panZoom(state))
        val local = IntOffset(75, 50)
        val anchor = DoubleOffset(local.x.toDouble(), local.y.toDouble())
        val before = state.localToContent(anchor)

        assertEquals(
            InputResult.Consumed,
            node.onPointerEvent(PointerEvent.Scroll(local, deltaX = 0.0, deltaY = -1.0), local),
        )

        assertEquals(2.24, state.metrics.zoom, EPSILON)
        assertOffset(before, state.localToContent(anchor))
        assertEquals(
            InputResult.Ignored,
            node.onPointerEvent(
                PointerEvent.Drag(IntOffset.Zero, PointerButton.Primary, deltaX = 10.0, deltaY = 0.0),
                IntOffset.Zero,
            ),
        )
    }

    @Test
    fun horizontalOnlyScrollRemainsAvailableToOrdinaryDispatch() {
        val state = attachedState(initialZoom = 2.0)
        val node = pointerNode(Modifier.Empty.panZoom(state))

        assertEquals(
            InputResult.Ignored,
            node.onPointerEvent(PointerEvent.Scroll(IntOffset(25, 50), deltaX = 3.0, deltaY = 0.0), IntOffset(25, 50)),
        )
        assertEquals(2.0, state.metrics.zoom, EPSILON)
        assertOffset(DoubleOffset(500.0, 500.0), state.metrics.center)
    }

    @Test
    fun customPanButtonAndScrollStepAreValidatedAndApplied() {
        val state = attachedState(initialZoom = 2.0)
        val node = pointerNode(Modifier.Empty.panZoom(state, PointerButton.Middle, zoomStep = 2.0))

        assertEquals(InputResult.Ignored, node.onPointerEvent(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero))
        assertEquals(InputResult.Consumed, node.onPointerEvent(PointerEvent.Press(IntOffset.Zero, PointerButton.Middle), IntOffset.Zero))
        node.onPointerCaptureAcquired(PointerButton.Middle)
        node.onPointerCaptureCancelled(PointerButton.Middle)
        assertEquals(
            InputResult.Consumed,
            node.onPointerEvent(PointerEvent.Scroll(IntOffset(50, 50), deltaX = 0.0, deltaY = -1.0), IntOffset(50, 50)),
        )
        assertEquals(4.0, state.metrics.zoom, EPSILON)

        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.panZoom(state, zoomStep = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Modifier.Empty.panZoom(state, zoomStep = Double.NaN) }
    }

    @Test
    fun modifierUsesOneStableTypeAndUpdatesWithoutDirtyPhases() {
        val firstState = PanZoomState()
        val secondState = PanZoomState()
        val first =
            Modifier.Empty
                .panZoom(firstState)
                .elements()
                .single()
        val second =
            Modifier.Empty
                .panZoom(secondState, PointerButton.Middle, 2.0)
                .elements()
                .single()
        val node = first.type.createErased(first)

        assertTrue(node is PointerCaptureNode)
        assertEquals(DirtyMask.None, first.type.updateErased(first, second, node))
    }

    private fun attachedState(initialZoom: Double): PanZoomState {
        val state = PanZoomState(initialZoom = initialZoom, maximumZoom = 8.0)
        val observer = state.observe { _ -> }
        state.updateGeometry(
            contentBounds = LongRect(0L, 0L, 1000L, 1000L),
            viewportSize = IntSize(100, 100),
            fit = PanZoomFit.Cover,
            origin = observer,
        )
        return state
    }

    private fun pointerNode(modifier: Modifier): PointerCaptureNode {
        val element = modifier.elements().single()
        return element.type.createErased(element) as PointerCaptureNode
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
    }
}
