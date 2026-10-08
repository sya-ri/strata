package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies current child participation and sparse geometry on JVM and JavaScript.
 */
internal class ChildParticipationTest {
    @Test
    fun duplicateMeasureAndPlacementAndUnmeasuredReadsAndPlacementStayRejected() {
        val plans =
            listOf(
                Plan(listOf(0, 0), emptyList()),
                Plan(listOf(0), listOf(0, 0)),
                Plan(listOf(0), emptyList(), listOf(1)),
                Plan(listOf(0), listOf(1), emptyList()),
            )
        for (plan in plans) {
            val probe = TestProbe()
            val tree = UiTree()
            try {
                tree.update(ParticipationElement(plan, children(probe, 2)))
                assertFailsWith<IllegalStateException> {
                    tree.measure(Constraints(maxWidth = 40, maxHeight = 10))
                    tree.layout()
                }
                assertEquals(TreeState.Poisoned, tree.state)
            } finally {
                tree.close()
            }
        }
    }

    @Test
    fun sparseParticipationDoesNotReusePreviouslyMeasuredOrPlacedChildren() {
        val probe = TestProbe()
        val children = children(probe, 4_096)
        val tree = UiTree()
        try {
            tree.update(ParticipationElement(Plan(listOf(0, 4_095), listOf(4_095)), children))
            frame(tree)
            assertEquals(2, probe.measureCalls)
            assertVisible(tree, listOf(4_095))

            tree.update(ParticipationElement(Plan(listOf(0), listOf(0)), children))
            frame(tree)
            assertEquals(2, probe.measureCalls)
            assertVisible(tree, listOf(0))
            probe.nodeForTag(TestProbe.ProbeId("4095")).invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
            frame(tree)
            assertEquals(2, probe.measureCalls)
            assertVisible(tree, listOf(0))

            tree.update(ParticipationElement(Plan(listOf(4_095), listOf(4_095)), children))
            frame(tree)
            assertEquals(3, probe.measureCalls)
            assertVisible(tree, listOf(4_095))
        } finally {
            tree.close()
        }
    }

    @Test
    fun layoutOnlyChangesKeepMeasuredChildrenButReplaceSparsePlacement() {
        val probe = TestProbe()
        val children = children(probe, 2)
        val tree = UiTree()
        try {
            tree.update(ParticipationElement(Plan(listOf(0, 1), listOf(0)), children))
            frame(tree)
            assertVisible(tree, listOf(0))
            assertEquals(2, probe.measureCalls)

            tree.update(ParticipationElement(Plan(listOf(0, 1), listOf(1)), children))
            tree.layout()
            assertEquals(2, probe.measureCalls)
            assertVisible(tree, listOf(1))
        } finally {
            tree.close()
        }
    }

    @Test
    fun keyedReorderAndReplacementUseCurrentIndicesAndOffsets() {
        val probe = TestProbe()
        val initial = children(probe, 2)
        val plan = Plan(listOf(0, 1), listOf(0, 1))
        val tree = UiTree()
        try {
            tree.update(ParticipationElement(plan, initial))
            frame(tree)
            val first = probe.nodeForTag(TestProbe.ProbeId("0"))
            val second = probe.nodeForTag(TestProbe.ProbeId("1"))

            tree.update(ParticipationElement(plan, initial.reversed()))
            frame(tree)
            assertSame(first, probe.nodeForTag(TestProbe.ProbeId("0")))
            assertSame(second, probe.nodeForTag(TestProbe.ProbeId("1")))
            assertEquals(listOf(UiText.Literal("1"), UiText.Literal("0")), tree.semantics().map { it.semantics.label })
            assertGeometry(tree, listOf(0, 1))

            tree.update(ParticipationElement(plan, listOf(initial[1], probe.element(TestProbe.ProbeId("2"), key = TestProbe.ProbeId("2")))))
            frame(tree)
            assertSame(second, probe.nodeForTag(TestProbe.ProbeId("1")))
            assertEquals(listOf(UiText.Literal("1"), UiText.Literal("2")), tree.semantics().map { it.semantics.label })
            assertGeometry(tree, listOf(0, 1))
        } finally {
            tree.close()
        }
    }

    @Test
    fun modifierInsertionAndRemovalReplaceEffectiveParentParticipation() {
        val probe = TestProbe()
        val children = children(probe, 2)
        val plan = Plan(listOf(0, 1), listOf(1))
        val tree = UiTree()
        try {
            for (modifier in listOf(Modifier.Empty, Modifier.Empty.padding(1), Modifier.Empty.padding(2), Modifier.Empty)) {
                tree.update(ParticipationElement(plan, children, modifier))
                frame(tree)
                assertEquals(listOf(UiText.Literal("1")), tree.semantics().map { it.semantics.label })
                assertEquals(1, tree.paint().size)
                val bounds = tree.semantics().single().bounds
                probe.inputEvents.clear()
                assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset(bounds.left, bounds.top))))
                assertEquals(listOf(TestProbe.ProbeId("1")), probe.inputEvents)
            }
        } finally {
            tree.close()
        }
    }

    @Test
    fun cleanupClearsParticipationBeforeCallbacksEvenWhenDisposalFails() {
        val failure = IllegalStateException("dispose failure")
        val probe = TestProbe(failingDisposeTag = TestProbe.ProbeId("1"), disposeFailure = failure)
        val children = children(probe, 2)
        val description = probe.root(children)
        val root = RetainedNode(description, probe.create(description), null)
        for (childDescription in children) {
            val child = RetainedNode(childDescription, probe.create(childDescription), root)
            child.parent = root
            root.children.add(child)
        }
        val owner = OwnerGuard()
        val entries = listOf(root) + root.children
        val lifecycle =
            LifecycleManager(NodeOwnershipRegistry(), owner, DirtyTracker()) {
                for (entry in entries) {
                    assertNull(entry.childMeasurePass)
                    assertNull(entry.childLayoutPass)
                    assertNull(entry.parentMeasurePass)
                    assertNull(entry.parentLayoutPass)
                    assertNull(entry.parentOffset)
                }
            }
        entries.forEach(lifecycle::bind)
        lifecycle.attachPending(root)
        val pipeline = Pipeline(owner)
        pipeline.measure(root, Constraints(maxWidth = 40, maxHeight = 10))
        pipeline.layout(root)
        assertNotNull(root.childMeasurePass)
        assertNotNull(root.childLayoutPass)
        assertNotNull(root.children[0].parentOffset)

        assertSame(failure, lifecycle.cleanup(root))
        assertNull(lifecycle.cleanup(root))
    }

    private fun children(
        probe: TestProbe,
        count: Int,
    ): List<TestProbe.ProbeElement> = List(count) { index -> probe.element(TestProbe.ProbeId(index.toString()), key = TestProbe.ProbeId(index.toString())) }

    private fun frame(tree: UiTree) {
        tree.measure(Constraints(maxWidth = 20_000, maxHeight = 10))
        tree.layout()
    }

    private fun assertVisible(
        tree: UiTree,
        indices: List<Int>,
    ) {
        assertEquals(indices.map { UiText.Literal(it.toString()) }, tree.semantics().map { it.semantics.label })
        assertGeometry(tree, indices)
    }

    private fun assertGeometry(
        tree: UiTree,
        indices: List<Int>,
    ) {
        assertEquals(indices.size, tree.paint().size)
        assertEquals(indices.map { IntRect(it * 3, 0, it * 3 + 2, 1) }, tree.semantics().map { it.bounds })
    }

    /**
     * Exact child callbacks requested by one immutable test description.
     */
    private data class Plan(
        val measure: List<Int>,
        val place: List<Int>,
        val read: List<Int> = place,
    )

    /**
     * Parent fixture that permits sparse or deliberately invalid child participation.
     */
    private class ParticipationNode(
        var plan: Plan,
    ) : Node(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in plan.measure) scope.measureChild(index, constraints)
            return constraints.constrain(IntSize(20_000, 10))
        }

        override fun layout(scope: LayoutScope) {
            for (index in plan.read) scope.measuredChildSize(index)
            for (index in plan.place) scope.placeChild(index, IntOffset(index * 3, 0))
        }
    }

    /**
     * Immutable parent fixture preserving child identity through description changes.
     */
    private class ParticipationElement(
        val plan: Plan,
        children: List<Element>,
        modifier: Modifier = Modifier.Empty,
    ) : Element(ElementIdentity.Positional, TYPE, children = children, modifier = modifier) {
        /**
         * Stable parent token preserving compatible retained nodes.
         */
        companion object {
            val TYPE: ElementType<ParticipationElement, ParticipationNode> =
                ElementType(
                    elementClass = ParticipationElement::class,
                    nodeClass = ParticipationNode::class,
                    validateLocal = { _ -> },
                    createNode = { ParticipationNode(it.plan) },
                    updateNode = { previous, current, node ->
                        node.plan = current.plan
                        when {
                            previous.plan == current.plan -> DirtyMask.None
                            previous.plan.measure == current.plan.measure -> DirtyMask.of(DirtyPhase.Layout)
                            else -> DirtyMask.of(DirtyPhase.Measure)
                        }
                    },
                )
        }
    }
}
