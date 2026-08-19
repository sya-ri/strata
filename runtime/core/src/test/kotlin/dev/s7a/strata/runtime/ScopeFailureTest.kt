package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies that invalid scope participation poisons and cleans the retained tree.
 */
internal class ScopeFailureTest {
    @Test
    fun scopeContractFailuresPoisonAndClean() {
        ScopeFailureStage.entries.forEach { stage ->
            val probe = ScopeFailureProbe()
            val tree = UiTree()
            tree.update(
                ScopeFailureElement(
                    probe,
                    ScopeFailureNodeId.Root,
                    stage,
                    listOf(ScopeFailureElement(probe, ScopeFailureNodeId.Child, stage)),
                ),
            )
            val expected = expectedFailure(stage)
            assertThrows(expected, { tree.measureOrLayout(stage) }, "Scope failure $stage")
            assertEquals(TreeState.Poisoned, tree.state)
            assertEquals(
                listOf(
                    ScopeFailureEvent.Attach(ScopeFailureNodeId.Root),
                    ScopeFailureEvent.Attach(ScopeFailureNodeId.Child),
                    ScopeFailureEvent.Detach(ScopeFailureNodeId.Child),
                    ScopeFailureEvent.Dispose(ScopeFailureNodeId.Child),
                    ScopeFailureEvent.Detach(ScopeFailureNodeId.Root),
                    ScopeFailureEvent.Dispose(ScopeFailureNodeId.Root),
                ),
                probe.events,
            )
            tree.close()
        }
    }

    @Test
    fun absentCapabilitiesRemainValidAndProduceEmptyResults() {
        val tree = UiTree()
        tree.update(BareElement())
        assertEquals(IntSize.Zero, tree.measure(Constraints(maxWidth = 20, maxHeight = 20)))
        tree.layout()
        assertEquals(emptyList<DrawCommand>(), tree.paint())
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(emptyList<SemanticsEntry>(), tree.semantics())
        tree.close()
    }

    @Test
    fun measureReturningOutsideConstraintsPoisonsAndCleans() {
        val probe = ScopeFailureProbe()
        val tree = UiTree()
        tree.update(ScopeFailureElement(probe, ScopeFailureNodeId.Root, ScopeFailureStage.MeasureOutsideConstraints))

        assertThrows(IllegalStateException::class.java) {
            tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        }

        assertEquals(TreeState.Poisoned, tree.state)
        val events =
            listOf(
                ScopeFailureEvent.Attach(ScopeFailureNodeId.Root),
                ScopeFailureEvent.Detach(ScopeFailureNodeId.Root),
                ScopeFailureEvent.Dispose(ScopeFailureNodeId.Root),
            )
        assertEquals(events, probe.events)
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun measuringChildWithoutLayoutCapabilityPoisonsAndCleans() {
        val probe = ScopeFailureProbe()
        val tree = UiTree()
        tree.update(MeasureWithoutLayoutElement(probe, listOf(BareElement())))

        assertThrows(IllegalStateException::class.java) {
            tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        }

        assertEquals(TreeState.Poisoned, tree.state)
        val events =
            listOf(
                ScopeFailureEvent.Attach(ScopeFailureNodeId.Root),
                ScopeFailureEvent.Detach(ScopeFailureNodeId.Root),
                ScopeFailureEvent.Dispose(ScopeFailureNodeId.Root),
            )
        assertEquals(events, probe.events)
        tree.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun accumulatedPlacementOverflowPoisonsAndCleans() {
        val probe = ScopeFailureProbe()
        val tree = UiTree()
        tree.update(
            OverflowElement(
                probe,
                ScopeFailureElement(probe, ScopeFailureNodeId.Child, ScopeFailureStage.MeasureOutOfRange),
            ),
        )
        tree.measure(Constraints.fixed(1, 1))

        assertThrows(ArithmeticException::class.java) { tree.layout() }

        assertEquals(TreeState.Poisoned, tree.state)
        val events =
            listOf(
                ScopeFailureEvent.Attach(ScopeFailureNodeId.Root),
                ScopeFailureEvent.Attach(ScopeFailureNodeId.Child),
                ScopeFailureEvent.Detach(ScopeFailureNodeId.Child),
                ScopeFailureEvent.Dispose(ScopeFailureNodeId.Child),
                ScopeFailureEvent.Detach(ScopeFailureNodeId.Root),
                ScopeFailureEvent.Dispose(ScopeFailureNodeId.Root),
            )
        assertEquals(events, probe.events)
        tree.close()
        assertEquals(events, probe.events)
    }

    private fun expectedFailure(stage: ScopeFailureStage): Class<out Throwable> =
        when (stage) {
            ScopeFailureStage.MeasureOutOfRange,
            ScopeFailureStage.LayoutMeasuredOutOfRange,
            ScopeFailureStage.LayoutPlacementOutOfRange,
            -> IllegalArgumentException::class.java

            ScopeFailureStage.MeasureDuplicate,
            ScopeFailureStage.MeasureOutsideConstraints,
            ScopeFailureStage.LayoutUnmeasuredRead,
            ScopeFailureStage.LayoutUnmeasuredPlacement,
            ScopeFailureStage.LayoutDuplicatePlacement,
            -> IllegalStateException::class.java
        }

    private fun UiTree.measureOrLayout(stage: ScopeFailureStage) {
        when (stage) {
            ScopeFailureStage.MeasureOutOfRange,
            ScopeFailureStage.MeasureDuplicate,
            ScopeFailureStage.MeasureOutsideConstraints,
            -> {
                measure(Constraints(maxWidth = 20, maxHeight = 20))
            }

            ScopeFailureStage.LayoutMeasuredOutOfRange,
            ScopeFailureStage.LayoutPlacementOutOfRange,
            ScopeFailureStage.LayoutUnmeasuredRead,
            ScopeFailureStage.LayoutUnmeasuredPlacement,
            ScopeFailureStage.LayoutDuplicatePlacement,
            -> {
                measure(Constraints(maxWidth = 20, maxHeight = 20))
                layout()
            }
        }
    }

    /**
     * Typed node identities used by the lifecycle fixture.
     */
    private enum class ScopeFailureNodeId {
        /**
         * The retained root node.
         */
        Root,

        /**
         * The retained direct child node.
         */
        Child,
    }

    /**
     * Invalid scope operations covered by the fixture.
     */
    private enum class ScopeFailureStage {
        /**
         * Measures an index outside the direct-child range.
         */
        MeasureOutOfRange,

        /**
         * Measures one direct child twice.
         */
        MeasureDuplicate,

        /**
         * Returns a size outside the supplied constraints.
         */
        MeasureOutsideConstraints,

        /**
         * Reads a measured child index outside the direct-child range.
         */
        LayoutMeasuredOutOfRange,

        /**
         * Places a child index outside the direct-child range.
         */
        LayoutPlacementOutOfRange,

        /**
         * Reads a child that was not measured in the pass.
         */
        LayoutUnmeasuredRead,

        /**
         * Places a child that was not measured in the pass.
         */
        LayoutUnmeasuredPlacement,

        /**
         * Places one direct child twice.
         */
        LayoutDuplicatePlacement,
    }

    /**
     * Typed lifecycle observations for scope failures.
     */
    private sealed interface ScopeFailureEvent {
        /**
         * An attach callback observation.
         */
        data class Attach(
            val id: ScopeFailureNodeId,
        ) : ScopeFailureEvent

        /**
         * A detach callback observation.
         */
        data class Detach(
            val id: ScopeFailureNodeId,
        ) : ScopeFailureEvent

        /**
         * A dispose callback observation.
         */
        data class Dispose(
            val id: ScopeFailureNodeId,
        ) : ScopeFailureEvent
    }

    /**
     * Owns lifecycle observations for one scope failure tree.
     */
    private class ScopeFailureProbe {
        val events: MutableList<ScopeFailureEvent> = ArrayList()

        /**
         * Records an attach callback for [id].
         */
        fun attach(id: ScopeFailureNodeId) {
            events.add(ScopeFailureEvent.Attach(id))
        }

        /**
         * Records a detach callback for [id].
         */
        fun detach(id: ScopeFailureNodeId) {
            events.add(ScopeFailureEvent.Detach(id))
        }

        /**
         * Records a dispose callback for [id].
         */
        fun dispose(id: ScopeFailureNodeId) {
            events.add(ScopeFailureEvent.Dispose(id))
        }
    }

    /**
     * Node that performs one deliberately invalid scope operation.
     */
    private class ScopeFailureNode(
        private val probe: ScopeFailureProbe,
        private val id: ScopeFailureNodeId,
        private val stage: ScopeFailureStage,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            if (id === ScopeFailureNodeId.Root) {
                when (stage) {
                    ScopeFailureStage.MeasureOutOfRange -> {
                        scope.measureChild(1, constraints)
                    }

                    ScopeFailureStage.MeasureDuplicate -> {
                        scope.measureChild(0, constraints)
                        scope.measureChild(0, constraints)
                    }

                    ScopeFailureStage.MeasureOutsideConstraints -> {
                        return IntSize(21, 21)
                    }

                    ScopeFailureStage.LayoutUnmeasuredRead -> {}

                    ScopeFailureStage.LayoutUnmeasuredPlacement -> {}

                    else -> {
                        scope.measureChild(0, constraints)
                    }
                }
            }
            return constraints.constrain(IntSize(2, 2))
        }

        override fun layout(scope: LayoutScope) {
            if (id !== ScopeFailureNodeId.Root) {
                return
            }
            when (stage) {
                ScopeFailureStage.LayoutMeasuredOutOfRange -> {
                    scope.measuredChildSize(1)
                }

                ScopeFailureStage.LayoutPlacementOutOfRange -> {
                    scope.placeChild(1, IntOffset.Zero)
                }

                ScopeFailureStage.LayoutUnmeasuredRead -> {
                    scope.measuredChildSize(0)
                }

                ScopeFailureStage.LayoutUnmeasuredPlacement -> {
                    scope.placeChild(0, IntOffset.Zero)
                }

                ScopeFailureStage.LayoutDuplicatePlacement -> {
                    scope.placeChild(0, IntOffset.Zero)
                    scope.placeChild(0, IntOffset.Zero)
                }

                else -> {
                    scope.placeChild(0, IntOffset.Zero)
                }
            }
        }

        override fun attach() {
            probe.attach(id)
        }

        override fun detach() {
            probe.detach(id)
        }

        override fun dispose() {
            probe.dispose(id)
        }
    }

    /**
     * Typed description for the scope failure node.
     */
    private class ScopeFailureElement(
        private val probe: ScopeFailureProbe,
        private val id: ScopeFailureNodeId,
        private val stage: ScopeFailureStage,
        children: List<Element> = emptyList(),
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        /**
         * Stable token for the scope failure fixture.
         */
        companion object {
            val TYPE: ElementType<ScopeFailureElement, ScopeFailureNode> =
                ElementType(
                    elementClass = ScopeFailureElement::class,
                    nodeClass = ScopeFailureNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> ScopeFailureNode(element.probe, element.id, element.stage) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Description whose node measures a child but cannot place it.
     */
    private class MeasureWithoutLayoutElement(
        private val probe: ScopeFailureProbe,
        children: List<Element>,
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        /**
         * Stable token for the missing-layout capability fixture.
         */
        companion object {
            val TYPE: ElementType<MeasureWithoutLayoutElement, MeasureWithoutLayoutNode> =
                ElementType(
                    elementClass = MeasureWithoutLayoutElement::class,
                    nodeClass = MeasureWithoutLayoutNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> MeasureWithoutLayoutNode(element.probe) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Measures a child without implementing the required layout capability.
     */
    private class MeasureWithoutLayoutNode(
        private val probe: ScopeFailureProbe,
    ) : Node(),
        MeasureNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, constraints)
            return constraints.constrain(IntSize(1, 1))
        }

        override fun attach() {
            probe.attach(ScopeFailureNodeId.Root)
        }

        override fun detach() {
            probe.detach(ScopeFailureNodeId.Root)
        }

        override fun dispose() {
            probe.dispose(ScopeFailureNodeId.Root)
        }
    }

    /**
     * Description whose node places a measured child beyond integer coordinates.
     */
    private class OverflowElement(
        private val probe: ScopeFailureProbe,
        child: Element,
    ) : Element(ElementIdentity.Positional, TYPE, listOf(child)) {
        /**
         * Stable token for the checked-placement fixture.
         */
        companion object {
            val TYPE: ElementType<OverflowElement, OverflowNode> =
                ElementType(
                    elementClass = OverflowElement::class,
                    nodeClass = OverflowNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> OverflowNode(element.probe) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Places a child at an offset whose accumulated extent overflows.
     */
    private class OverflowNode(
        private val probe: ScopeFailureProbe,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, constraints)
            return constraints.constrain(IntSize(1, 1))
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset(Int.MAX_VALUE, 0))
        }

        override fun attach() {
            probe.attach(ScopeFailureNodeId.Root)
        }

        override fun detach() {
            probe.detach(ScopeFailureNodeId.Root)
        }

        override fun dispose() {
            probe.dispose(ScopeFailureNodeId.Root)
        }
    }

    /**
     * Element whose node implements no optional pipeline capability.
     */
    private class BareElement : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable token for the capability absence fixture.
         */
        companion object {
            val TYPE: ElementType<BareElement, BareNode> =
                ElementType(
                    elementClass = BareElement::class,
                    nodeClass = BareNode::class,
                    validateLocal = { _ -> },
                    createNode = { BareNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Node with no measure, layout, paint, input, or semantics capability.
     */
    private class BareNode : Node()
}
