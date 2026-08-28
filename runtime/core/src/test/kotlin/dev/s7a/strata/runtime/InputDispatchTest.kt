package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PointerCaptureNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun thirdPartyPrimitiveCapturesAcrossAncestorClipsAndUsesNewLayoutCoordinates() {
        val probe = CaptureProbe()
        val tree = UiTree()
        tree.update(PlacementElement(CaptureElement(probe), offset = IntOffset(2, 2)))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(3, 3), PointerButton.Primary))
        val clipped = PointerEvent.Drag(IntOffset(15, 16), PointerButton.Primary, 12.0, 13.0)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(clipped))
        assertEquals(clipped to IntOffset(13, 14), probe.events.last())

        tree.update(PlacementElement(CaptureElement(probe), offset = IntOffset(5, 6)))
        layout(tree)
        val release = PointerEvent.Release(IntOffset(12, 13), PointerButton.Primary)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(release))
        assertEquals(release to IntOffset(7, 7), probe.events.last())
        assertEquals(InputResult.Ignored, tree.dispatchPointer(clipped))
        tree.close()
        assertEquals(listOf(CaptureStage.Detach, CaptureStage.Dispose), probe.cleanup)
    }

    @Test
    fun unplacingAnAncestorCancelsEvenWhenDescendantPlacementIsRetained() {
        val probe = CaptureProbe()
        val tree = UiTree()
        val child = PlacementElement(CaptureElement(probe))
        tree.update(PlacementElement(child))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))

        tree.update(PlacementElement(child, place = false))
        layout(tree)
        assertEquals(listOf(CaptureStage.Cancel), probe.cleanup)
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))

        tree.update(PlacementElement(child))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
        tree.close()
        assertEquals(listOf(CaptureStage.Cancel, CaptureStage.Cancel, CaptureStage.Detach, CaptureStage.Dispose), probe.cleanup)
    }

    @Test
    fun inputResetClearsHoveredNodesBelowUnplacedAncestorsWithoutRepeatingCaptureCancellation() {
        val probe = CaptureProbe()
        val hover = ArrayList<PointerHoverEvent>()
        val child = PlacementElement(CaptureElement(probe, Modifier.Empty.onHover(hover::add)))
        UiTree().use { tree ->
            tree.update(PlacementElement(child))
            layout(tree)
            tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
            tree.update(PlacementElement(child, place = false))
            layout(tree)
            tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            assertEquals(listOf(PointerHoverEvent.Enter), hover)
            assertEquals(listOf(CaptureStage.Cancel), probe.cleanup)

            tree.clearInputState()
            tree.clearInputState()
            assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit), hover)
            assertEquals(listOf(CaptureStage.Cancel), probe.cleanup)
            tree.update(PlacementElement(child))
            layout(tree)
            tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit, PointerHoverEvent.Enter), hover)
        }
    }

    @Test
    fun removingACapturedPrimitiveCancelsBeforeItsLifecycleResources() {
        val probe = CaptureProbe()
        val tree = UiTree()
        tree.update(CaptureElement(probe))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))

        tree.update(TestProbe().element(id("replacement")))
        assertEquals(listOf(CaptureStage.Cancel, CaptureStage.Detach, CaptureStage.Dispose), probe.cleanup)
        tree.close()
        assertEquals(1, probe.cleanup.count { stage -> stage === CaptureStage.Cancel })
    }

    @Test
    fun pointerFailureCancelsOnceAndPreservesCleanupSuppressionOrder() {
        val primary = IllegalArgumentException("pointer")
        val cancellation = IllegalStateException("cancel")
        val detach = IllegalStateException("detach")
        val dispose = IllegalStateException("dispose")
        val probe = CaptureProbe(inputFailure = primary, cancelFailure = cancellation, detachFailure = detach, disposeFailure = dispose)
        val tree = UiTree()
        tree.update(CaptureElement(probe))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                tree.dispatchPointer(PointerEvent.Move(IntOffset(20, 20)))
            }
        assertSame(primary, failure)
        assertEquals(listOf(cancellation, detach, dispose), failure.suppressed.toList())
        assertEquals(listOf(CaptureStage.Cancel, CaptureStage.Detach, CaptureStage.Dispose), probe.cleanup)
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
        assertEquals(1, probe.cleanup.count { stage -> stage === CaptureStage.Cancel })
    }

    @Test
    fun cancellationFailureOnCloseStillDisposesEveryNodeAndDoesNotRepeat() {
        val cancellation = IllegalArgumentException("cancel")
        val detach = IllegalStateException("detach")
        val dispose = IllegalStateException("dispose")
        val probe = CaptureProbe(cancelFailure = cancellation, detachFailure = detach, disposeFailure = dispose)
        val tree = UiTree()
        tree.update(PlacementElement(CaptureElement(probe)))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))

        val failure = assertThrows(IllegalArgumentException::class.java) { tree.close() }
        assertSame(cancellation, failure)
        assertEquals(listOf(detach, dispose), failure.suppressed.toList())
        assertEquals(TreeState.Closed, tree.state)
        tree.close()
        assertEquals(listOf(CaptureStage.Cancel, CaptureStage.Detach, CaptureStage.Dispose), probe.cleanup)
    }

    @Test
    fun removalCancellationFailureAlsoCleansTheUnattachedReplacement() {
        val cancellation = IllegalArgumentException("cancel")
        val probe = CaptureProbe(cancelFailure = cancellation)
        val replacement = TestProbe()
        val tree = UiTree()
        tree.update(CaptureElement(probe))
        layout(tree)
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                tree.update(replacement.root(emptyList()))
            }
        assertSame(cancellation, failure)
        assertEquals(listOf(CaptureStage.Cancel, CaptureStage.Detach, CaptureStage.Dispose), probe.cleanup)
        assertEquals(listOf(TestProbe.Event.Dispose(id("root"))), replacement.events)
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
        assertEquals(1, probe.cleanup.count { stage -> stage === CaptureStage.Cancel })
    }

    private fun id(value: String): TestProbe.ProbeId = TestProbe.ProbeId(value)

    private fun layout(tree: UiTree) {
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
    }

    private enum class CaptureStage {
        Cancel,
        Detach,
        Dispose,
    }

    private class CaptureProbe(
        val inputFailure: Throwable? = null,
        val cancelFailure: Throwable? = null,
        val detachFailure: Throwable? = null,
        val disposeFailure: Throwable? = null,
    ) {
        val events: MutableList<Pair<PointerEvent, IntOffset>> = ArrayList()
        val cleanup: MutableList<CaptureStage> = ArrayList()
    }

    private class CaptureElement(
        val probe: CaptureProbe,
        modifier: Modifier = Modifier.Empty,
    ) : Element(ElementIdentity.Positional, TYPE, modifier = modifier) {
        companion object {
            val TYPE: ElementType<CaptureElement, CaptureNode> =
                ElementType(
                    elementClass = CaptureElement::class,
                    nodeClass = CaptureNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> CaptureNode(element.probe) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class CaptureNode(
        private val probe: CaptureProbe,
    ) : Node(),
        MeasureNode,
        PointerCaptureNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(20, 20))

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            probe.events += event to localPosition
            if (event is PointerEvent.Move) probe.inputFailure?.let { throw it }
            return if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            assertEquals(PointerButton.Primary, button)
            probe.cleanup += CaptureStage.Cancel
            probe.cancelFailure?.let { throw it }
        }

        override fun attach() = Unit

        override fun detach() {
            probe.cleanup += CaptureStage.Detach
            probe.detachFailure?.let { throw it }
        }

        override fun dispose() {
            probe.cleanup += CaptureStage.Dispose
            probe.disposeFailure?.let { throw it }
        }
    }

    private class PlacementElement(
        child: Element,
        val offset: IntOffset = IntOffset.Zero,
        val place: Boolean = true,
    ) : Element(ElementIdentity.Positional, TYPE, listOf(child)) {
        companion object {
            val TYPE: ElementType<PlacementElement, PlacementNode> =
                ElementType(
                    elementClass = PlacementElement::class,
                    nodeClass = PlacementNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> PlacementNode(element.offset, element.place) },
                    updateNode = { previous, current, node ->
                        node.offset = current.offset
                        node.place = current.place
                        if (previous.offset == current.offset && previous.place == current.place) DirtyMask.None else DirtyMask.of(DirtyPhase.Layout)
                    },
                )
        }
    }

    private class PlacementNode(
        var offset: IntOffset,
        var place: Boolean,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ClipChildrenNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, Constraints())
            return constraints.constrain(IntSize(10, 10))
        }

        override fun layout(scope: LayoutScope) {
            if (place) scope.placeChild(0, offset)
        }
    }
}
