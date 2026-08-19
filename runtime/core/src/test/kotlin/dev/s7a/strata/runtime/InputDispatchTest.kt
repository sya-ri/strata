package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies reverse paint-order input, bubbling, half-open bounds, and overflow hit traversal.
 */
internal class InputDispatchTest {
    @Test
    fun consumedInputStopsAtTopmostOverlappingChild() {
        val probe = TestProbe(overlapChildren = true)
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("first")), probe.element(id("second")))))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 0))))
        assertEquals(listOf(id("second")), probe.inputEvents)
        tree.close()
    }

    @Test
    fun ignoredInputBubblesAndHalfOpenOverflowBoundsRemainReachable() {
        val probe = TestProbe(inputResult = InputResult.Ignored)
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("first")), probe.element(id("overflow")))))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 0))))
        assertEquals(listOf(id("first"), id("root")), probe.inputEvents)
        probe.inputEvents.clear()
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(2, 0))))
        assertEquals(listOf(id("overflow")), probe.inputEvents)
        probe.inputEvents.clear()
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(4, 0))))
        assertEquals(emptyList<TestProbe.ProbeId>(), probe.inputEvents)
        tree.close()
    }

    @Test
    fun inputCallbacksReceiveChildLocalCoordinates() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(id("first")), probe.element(id("second")))))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        val event = PointerEvent.Move(IntOffset(3, 0))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(event))
        assertEquals(
            listOf(TestProbe.InputObservation(id("second"), event, IntOffset(1, 0))),
            probe.inputObservations,
        )
        tree.close()
    }

    private fun id(value: String): TestProbe.ProbeId = TestProbe.ProbeId(value)
}
