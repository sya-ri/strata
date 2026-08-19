package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies geometry and the first retained-tree pipeline slice.
 */
internal class UiTreeTest {
    @Test
    fun geometryIsCheckedAndHalfOpen() {
        assertEquals(IntSize(3, 4), IntRect(1, 2, 4, 6).size)
        assertEquals(true, IntOffset(1, 2) in IntRect(1, 2, 4, 6))
        assertEquals(false, IntOffset(4, 2) in IntRect(1, 2, 4, 6))
        assertThrows(ArithmeticException::class.java) {
            IntOffset(Int.MAX_VALUE, 0) + IntOffset(1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Constraints(minWidth = 4, maxWidth = 3)
        }
    }

    @Test
    fun updatesReuseNodeAndInvalidateOnlyPaint() {
        val tree = UiTree()
        tree.update(TestElement(ArgbColor(0xFF00FF00.toInt())))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        tree.paint()
        tree.update(TestElement(ArgbColor(0xFFFF0000.toInt())))
        val commands = tree.paintAfterMeasureAndLayout()
        assertEquals(ArgbColor(0xFFFF0000.toInt()), (commands.single() as DrawCommand.FillRectangle).color)
        assertThrows(UnsupportedOperationException::class.java) {
            (commands as MutableList<DrawCommand>).add(commands.single())
        }
        assertEquals(
            InputResult.Consumed,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
        )
        val semantics = tree.semantics()
        assertEquals(UiText.Literal("test"), semantics.single().semantics.label)
        assertThrows(UnsupportedOperationException::class.java) {
            (semantics as MutableList<SemanticsEntry>).add(semantics.single())
        }
        tree.close()
    }

    @Test
    fun duplicateKeysFailBeforeLifecycleMutation() {
        val tree = UiTree()
        val first = TestElement(key = "same")
        val second = TestElement(key = "same")
        assertThrows(IllegalArgumentException::class.java) {
            tree.update(TestElement(children = listOf(first, second)))
        }
        assertEquals(TreeState.Active, tree.state)
        tree.close()
    }

    @Test
    fun validationFailuresPreserveCachedTreeForRetry() {
        val probe = TestProbe()
        val tree = UiTree()
        val childTag = TestProbe.ProbeId("child")
        val childKey = TestProbe.ProbeId("child-key")
        tree.update(probe.root(listOf(probe.element(childTag, childKey))))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        val oldPaint = tree.paint()
        val oldSemantics = tree.semantics()
        val oldNode = probe.nodeForTag(childTag)
        val updateCalls = probe.updateCalls
        val measureCalls = probe.measureCalls
        val layoutCalls = probe.layoutCalls
        val paintCalls = probe.paintCalls
        val semanticsCalls = probe.semanticsCalls
        val events = probe.events.toList()

        assertValidationFailurePreservesTree(
            tree,
            probe,
            probe.root(
                listOf(
                    probe.element(TestProbe.ProbeId("duplicate-first"), childKey),
                    probe.element(TestProbe.ProbeId("duplicate-second"), childKey),
                ),
            ),
            oldPaint,
            oldSemantics,
            updateCalls,
            measureCalls,
            layoutCalls,
            paintCalls,
            semanticsCalls,
            events,
        )
        assertValidationFailurePreservesTree(
            tree,
            probe,
            probe.root(listOf(probe.element(TestProbe.ProbeId(""), childKey))),
            oldPaint,
            oldSemantics,
            updateCalls,
            measureCalls,
            layoutCalls,
            paintCalls,
            semanticsCalls,
            events,
        )

        val updatedTag = TestProbe.ProbeId("updated")
        tree.update(probe.root(listOf(probe.element(updatedTag, childKey))))
        assertSame(oldNode, probe.nodeForTag(updatedTag))
        assertEquals(oldPaint, tree.paint())
        assertEquals(paintCalls, probe.paintCalls)
        val updatedSemantics = tree.semantics()
        assertEquals(UiText.Literal("updated"), updatedSemantics.last().semantics.label)
        assertEquals(semanticsCalls + 1, probe.semanticsCalls)
        tree.close()
    }

    /**
     * Verifies that one description validation failure leaves cached retained state untouched.
     */
    private fun assertValidationFailurePreservesTree(
        tree: UiTree,
        probe: TestProbe,
        invalid: Element,
        oldPaint: List<DrawCommand>,
        oldSemantics: List<SemanticsEntry>,
        updateCalls: Int,
        measureCalls: Int,
        layoutCalls: Int,
        paintCalls: Int,
        semanticsCalls: Int,
        events: List<TestProbe.Event>,
    ) {
        assertThrows(IllegalArgumentException::class.java) { tree.update(invalid) }
        assertEquals(TreeState.Active, tree.state)
        assertEquals(updateCalls, probe.updateCalls)
        assertEquals(measureCalls, probe.measureCalls)
        assertEquals(layoutCalls, probe.layoutCalls)
        assertEquals(paintCalls, probe.paintCalls)
        assertEquals(semanticsCalls, probe.semanticsCalls)
        assertEquals(events, probe.events)
        assertEquals(oldPaint, tree.paint())
        assertEquals(oldSemantics, tree.semantics())
        assertEquals(paintCalls, probe.paintCalls)
        assertEquals(semanticsCalls, probe.semanticsCalls)
    }

    @Test
    fun stateRejectsWrongThreadAccess() {
        val tree = UiTree()
        var failure: Throwable? = null
        val otherThread =
            Thread {
                failure = runCatching { tree.state }.exceptionOrNull()
            }
        otherThread.start()
        otherThread.join()
        assertTrue(failure is IllegalStateException)
        tree.close()
    }

    @Test
    fun emptyTreeOperationsAreNoOpsAndCloseIsIdempotent() {
        val tree = UiTree()
        assertEquals(IntSize.Zero, tree.measure(Constraints(maxWidth = 10, maxHeight = 10)))
        tree.layout()
        assertEquals(emptyList<DrawCommand>(), tree.paint())
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(emptyList<SemanticsEntry>(), tree.semantics())
        tree.close()
        tree.close()
        assertEquals(TreeState.Closed, tree.state)
    }

    @Test
    fun nonEmptyPipelinePreconditionsPoisonAndClean() {
        PreconditionStage.entries.forEach { stage ->
            val probe = TestProbe()
            val tree = UiTree()
            tree.update(probe.root(emptyList()))
            probe.events.clear()

            assertThrows(IllegalStateException::class.java, { tree.invokePrecondition(stage) }, "Precondition $stage")
            assertEquals(TreeState.Poisoned, tree.state)
            val events =
                listOf(
                    TestProbe.Event.Detach(TestProbe.ProbeId("root")),
                    TestProbe.Event.Dispose(TestProbe.ProbeId("root")),
                )
            assertEquals(events, probe.events)
            tree.close()
            assertEquals(events, probe.events)
        }
    }

    private fun UiTree.invokePrecondition(stage: PreconditionStage) {
        when (stage) {
            PreconditionStage.LayoutBeforeMeasure -> layout()
            PreconditionStage.PaintBeforeLayout -> paint()
            PreconditionStage.InputBeforeLayout -> dispatchPointer(PointerEvent.Move(IntOffset.Zero))
            PreconditionStage.SemanticsBeforeLayout -> semantics()
        }
    }

    /**
     * Non-empty pipeline preconditions that reject stale geometry.
     */
    private enum class PreconditionStage {
        /**
         * Attempts layout before measurement.
         */
        LayoutBeforeMeasure,

        /**
         * Attempts paint before layout.
         */
        PaintBeforeLayout,

        /**
         * Attempts input dispatch before layout.
         */
        InputBeforeLayout,

        /**
         * Attempts semantics collection before layout.
         */
        SemanticsBeforeLayout,
    }

    /**
     * Runs the required pipeline stages before painting.
     *
     * @return the resulting commands.
     */
    private fun UiTree.paintAfterMeasureAndLayout(): List<DrawCommand> {
        measure(Constraints(maxWidth = 10, maxHeight = 10))
        layout()
        return paint()
    }

    private class TestNode :
        Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        PointerInputNode,
        SemanticsNode,
        LifecycleNode {
        var color = ArgbColor(0)

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(2, 2))

        override fun layout(scope: LayoutScope) = Unit

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult = InputResult.Consumed

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = UiText.Literal("test")))
        }

        /**
         * Updates the test color and invalidates painting when it changes.
         *
         * @param next the incoming fill color.
         */
        fun updateColor(next: ArgbColor) {
            if (color != next) {
                color = next
            }
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() = Unit
    }

    private class TestElement(
        private val color: ArgbColor = ArgbColor(0xFF00FF00.toInt()),
        key: Any? = null,
        children: List<Element> = emptyList(),
    ) : Element(
            identity = key?.let { ElementIdentity.Keyed(ElementKey(it)) } ?: ElementIdentity.Positional,
            type = TYPE,
            children = children,
        ) {
        /**
         * Stable token for the test primitive.
         */
        companion object {
            val TYPE: ElementType<TestElement, TestNode> =
                ElementType(
                    elementClass = TestElement::class,
                    nodeClass = TestNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> TestNode().also { node -> node.color = element.color } },
                    updateNode = { previous, current, node ->
                        if (previous.color == current.color) {
                            DirtyMask.None
                        } else {
                            node.updateColor(current.color)
                            DirtyMask.of(DirtyPhase.Paint)
                        }
                    },
                )
        }
    }
}
