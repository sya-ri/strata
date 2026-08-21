package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies callback scope lifetime, thread confinement, caching, and phase invalidation.
 */
internal class ScopeLifetimeTest {
    @Test
    fun scopesCloseAfterCallbacksAndRejectConcurrentAccess() {
        val probe = ScopeProbe()
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        tree.measure(Constraints.fixed(4, 4))
        tree.layout()
        assertEquals(IntSize(4, 4), probe.layoutSize)
        tree.paint()
        tree.semantics()

        assertEquals(1, probe.overlayPaintCalls)
        assertEquals(1, probe.measureThreadFailures.size)
        assertEquals(1, probe.layoutThreadFailures.size)
        assertEquals(1, probe.paintThreadFailures.size)
        assertEquals(1, probe.overlayPaintThreadFailures.size)
        assertEquals(1, probe.semanticsThreadFailures.size)
        assertThrows(IllegalStateException::class.java) { requireNotNull(probe.measureScope).childCount }
        assertThrows(IllegalStateException::class.java) { requireNotNull(probe.layoutScope).size }
        assertThrows(IllegalStateException::class.java) { requireNotNull(probe.paintScope).size }
        assertThrows(IllegalStateException::class.java) { requireNotNull(probe.overlayPaintScope).size }
        assertThrows(IllegalStateException::class.java) {
            requireNotNull(probe.semanticsScope).emit(Semantics(label = UiText.Literal("late")))
        }
        tree.close()
    }

    @Test
    fun scopeClosesWhenItsCallbackThrows() {
        val probe = ScopeProbe(throwFromMeasure = true)
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        assertThrows(IllegalStateException::class.java) {
            tree.measure(Constraints.fixed(4, 4))
        }
        assertThrows(IllegalStateException::class.java) { requireNotNull(probe.measureScope).childCount }
        tree.close()
    }

    @Test
    fun overlayPaintScopeClosesAndPoisonsTreeWhenItsCallbackThrows() {
        val probe = ScopeProbe(throwFromOverlayPaint = true)
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        tree.measure(Constraints.fixed(4, 4))
        tree.layout()

        assertThrows(IllegalStateException::class.java) { tree.paint() }
        assertThrows(IllegalStateException::class.java) { requireNotNull(probe.overlayPaintScope).size }
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun semanticsAreCachedUntilInvalidatedAndSelfInvalidationSurvives() {
        val probe = ScopeProbe(selfInvalidateSemantics = true)
        val tree = UiTree()
        tree.update(ScopeElement(probe, UiText.Literal("first")))
        tree.measure(Constraints.fixed(4, 4))
        tree.layout()

        tree.semantics()
        assertEquals(1, probe.semanticsCalls)
        tree.semantics()
        assertEquals(2, probe.semanticsCalls)
        tree.semantics()
        assertEquals(2, probe.semanticsCalls)

        tree.update(ScopeElement(probe, UiText.Literal("second")))
        tree.semantics()
        assertEquals(3, probe.semanticsCalls)
        tree.close()
    }

    @Test
    fun measureLayoutAndPaintSelfInvalidationRerunsEachPhase() {
        val probe = ScopeProbe(selfInvalidateMeasure = true, selfInvalidateLayout = true, selfInvalidatePaint = true)
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        tree.measure(Constraints.fixed(4, 4))
        assertEquals(1, probe.measureCalls)
        tree.measure(Constraints.fixed(4, 4))
        assertEquals(2, probe.measureCalls)
        tree.layout()
        assertEquals(1, probe.layoutCalls)
        tree.layout()
        assertEquals(2, probe.layoutCalls)
        tree.paint()
        assertEquals(1, probe.paintCalls)
        tree.paint()
        assertEquals(2, probe.paintCalls)
        tree.close()
    }

    @Test
    fun measureSelfInvalidationBlocksLayoutUntilRemeasure() {
        val probe = ScopeProbe(selfInvalidateMeasure = true)
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        tree.measure(Constraints.fixed(4, 4))

        assertThrows(IllegalStateException::class.java) { tree.layout() }
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
        tree.close()
        assertEquals(TreeState.Closed, tree.state)
    }

    @Test
    fun placedChildLayoutInvalidationBlocksPaintThroughDescendantTraversal() {
        val probe = TestProbe()
        val tree = UiTree()
        tree.update(probe.root(listOf(probe.element(TestProbe.ProbeId("child")))))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        val child = probe.nodeForTag(TestProbe.ProbeId("child"))
        child.invalidateForTest(DirtyMask.of(DirtyPhase.Layout))
        probe.events.clear()

        assertThrows(IllegalStateException::class.java) { tree.paint() }
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                TestProbe.Event.Detach(TestProbe.ProbeId("child")),
                TestProbe.Event.Dispose(TestProbe.ProbeId("child")),
                TestProbe.Event.Detach(TestProbe.ProbeId("root")),
                TestProbe.Event.Dispose(TestProbe.ProbeId("root")),
            ),
            probe.events,
        )
        tree.close()
        assertEquals(TreeState.Closed, tree.state)
    }

    @Test
    fun pendingLayoutRejectsPaintInputAndSemanticsAfterRootInvalidation() {
        PendingGeometryStage.entries.forEach { stage ->
            val probe = ScopeProbe()
            val tree = UiTree()
            tree.update(ScopeElement(probe))
            tree.measure(Constraints.fixed(4, 4))
            tree.layout()
            probe.invalidateLayoutForTest()

            assertThrows(IllegalStateException::class.java, { tree.invokePendingGeometry(stage) }, "Pending $stage")
            assertEquals(TreeState.Poisoned, tree.state)
            tree.close()
            tree.close()
            assertEquals(TreeState.Closed, tree.state)
        }
    }

    private fun UiTree.invokePendingGeometry(stage: PendingGeometryStage) {
        when (stage) {
            PendingGeometryStage.Paint -> paint()
            PendingGeometryStage.Input -> dispatchPointer(PointerEvent.Move(IntOffset.Zero))
            PendingGeometryStage.Semantics -> semantics()
        }
    }

    /**
     * Operations that must reject geometry while a placed root remains layout-dirty.
     */
    private enum class PendingGeometryStage {
        /**
         * Attempts to paint pending geometry.
         */
        Paint,

        /**
         * Attempts pointer dispatch over pending geometry.
         */
        Input,

        /**
         * Attempts semantics collection over pending geometry.
         */
        Semantics,
    }

    @Test
    fun changedConstraintsRefreshEqualSizeDownstreamPhases() {
        val probe = ScopeProbe()
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        assertEquals(1, probe.measureCalls)
        assertEquals(1, probe.layoutCalls)
        assertEquals(1, probe.paintCalls)
        assertEquals(1, probe.semanticsCalls)

        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        tree.paint()
        tree.semantics()
        assertEquals(2, probe.measureCalls)
        assertEquals(2, probe.layoutCalls)
        assertEquals(2, probe.paintCalls)
        assertEquals(2, probe.semanticsCalls)
        tree.close()
    }

    @Test
    fun optionalChildrenDoNotBlockAndMeasuredUnplacedDirtyChildWaitsForPlacement() {
        val first = ParticipationProbe()
        val second = ParticipationProbe(invalidateLayoutDuringMeasure = true)
        val parent = ParticipationProbe()
        val tree = UiTree()
        val children = listOf(ChildElement(first), ChildElement(second))
        tree.update(ParentElement(parent, measureAll = true, placeSecond = false, children = children))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        assertEquals(1, first.measureCalls)
        assertEquals(1, second.measureCalls)
        assertEquals(1, first.layoutCalls)
        assertEquals(0, second.layoutCalls)
        assertEquals(1, first.paintCalls)
        assertEquals(0, second.paintCalls)
        assertEquals(1, first.semanticsCalls)
        assertEquals(0, second.semanticsCalls)

        tree.update(ParentElement(parent, measureAll = true, placeSecond = true, children = children))
        tree.layout()
        tree.paint()
        tree.semantics()
        assertEquals(1, second.layoutCalls)
        assertEquals(1, second.paintCalls)
        assertEquals(1, second.semanticsCalls)
        tree.close()
    }

    @Test
    fun wrongThreadNodeInvalidationFailsBeforeDirtyingTheTree() {
        val probe = ScopeProbe()
        val tree = UiTree()
        tree.update(ScopeElement(probe))
        tree.measure(Constraints.fixed(4, 4))
        tree.layout()
        tree.paint()
        val before = probe.paintCalls
        var failure: Throwable? = null
        val thread =
            Thread {
                failure = runCatching { requireNotNull(probe.node).invalidatePaintForTest() }.exceptionOrNull()
            }
        thread.start()
        thread.join()
        assertEquals(true, failure is IllegalStateException)
        tree.paint()
        assertEquals(before, probe.paintCalls)
        tree.close()
    }

    @Test
    fun descendantRemeasureReachesAncestorsButLeavesSiblingCachesClean() {
        val first = ParticipationProbe()
        val second = ParticipationProbe()
        val parent = ParticipationProbe()
        val tree = UiTree()

        fun description(secondSize: Int): ParentElement =
            ParentElement(
                parent,
                measureAll = true,
                placeSecond = true,
                children = listOf(ChildElement(first, 2), ChildElement(second, secondSize)),
            )

        tree.update(description(2))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        tree.update(description(3))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        assertEquals(2, parent.measureCalls)
        assertEquals(1, first.measureCalls)
        assertEquals(2, second.measureCalls)
        assertEquals(2, parent.layoutCalls)
        assertEquals(1, first.layoutCalls)
        assertEquals(2, second.layoutCalls)
        assertEquals(2, parent.paintCalls)
        assertEquals(1, first.paintCalls)
        assertEquals(2, second.paintCalls)
        assertEquals(2, parent.semanticsCalls)
        assertEquals(1, first.semanticsCalls)
        assertEquals(2, second.semanticsCalls)
        tree.close()
    }

    @Test
    fun layoutOnlyPlacementChangeReusesChildCachesAndTranslatesBounds() {
        val parent = ParticipationProbe()
        val child = ParticipationProbe()
        val tree = UiTree()

        fun description(offset: IntOffset): ParentElement =
            ParentElement(
                parent,
                measureAll = true,
                placeSecond = false,
                firstChildOffset = offset,
                children = listOf(ChildElement(child)),
            )

        tree.update(description(IntOffset.Zero))
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        val initialPaint = tree.paint()
        val initialSemantics = tree.semantics()
        assertEquals(IntRect(0, 0, 2, 2), (initialPaint.last() as DrawCommand.FillRectangle).bounds)
        assertEquals(IntRect(0, 0, 2, 2), initialSemantics.last().bounds)
        assertEquals(1, parent.measureCalls)
        assertEquals(1, child.measureCalls)
        assertEquals(1, parent.layoutCalls)
        assertEquals(1, child.layoutCalls)
        assertEquals(1, parent.measuredChildSizeCalls)
        assertEquals(IntSize(2, 2), parent.lastMeasuredChildSize)
        assertEquals(1, parent.paintCalls)
        assertEquals(1, child.paintCalls)
        assertEquals(1, parent.semanticsCalls)
        assertEquals(1, child.semanticsCalls)

        tree.update(description(IntOffset(3, 4)))
        tree.layout()
        val movedPaint = tree.paint()
        val movedSemantics = tree.semantics()

        assertEquals(1, parent.measureCalls)
        assertEquals(1, child.measureCalls)
        assertEquals(2, parent.layoutCalls)
        assertEquals(1, child.layoutCalls)
        assertEquals(2, parent.measuredChildSizeCalls)
        assertEquals(IntSize(2, 2), parent.lastMeasuredChildSize)
        assertEquals(2, parent.paintCalls)
        assertEquals(1, child.paintCalls)
        assertEquals(2, parent.semanticsCalls)
        assertEquals(1, child.semanticsCalls)
        assertEquals(IntRect(3, 4, 5, 6), (movedPaint.last() as DrawCommand.FillRectangle).bounds)
        assertEquals(IntRect(3, 4, 5, 6), movedSemantics.last().bounds)
        tree.close()
    }

    private class ScopeProbe(
        private val selfInvalidateMeasure: Boolean = false,
        private val selfInvalidateLayout: Boolean = false,
        private val selfInvalidatePaint: Boolean = false,
        private val selfInvalidateSemantics: Boolean = false,
        private val throwFromMeasure: Boolean = false,
        private val throwFromOverlayPaint: Boolean = false,
    ) {
        private var measureInvalidated: Boolean = false
        private var layoutInvalidated: Boolean = false
        private var paintInvalidated: Boolean = false
        private var semanticsInvalidated: Boolean = false
        var measureScope: MeasureScope? = null
        var layoutScope: LayoutScope? = null
        var paintScope: PaintScope? = null
        var overlayPaintScope: PaintScope? = null
        var semanticsScope: SemanticsScope? = null
        var layoutSize: IntSize = IntSize.Zero
        val measureThreadFailures: MutableList<Throwable> = ArrayList()
        val layoutThreadFailures: MutableList<Throwable> = ArrayList()
        val paintThreadFailures: MutableList<Throwable> = ArrayList()
        val overlayPaintThreadFailures: MutableList<Throwable> = ArrayList()
        val semanticsThreadFailures: MutableList<Throwable> = ArrayList()
        var measureCalls: Int = 0
        var layoutCalls: Int = 0
        var paintCalls: Int = 0
        var overlayPaintCalls: Int = 0
        var semanticsCalls: Int = 0
        var node: ScopeNode? = null

        /**
         * Creates the test node for one description.
         */
        fun create(element: ScopeElement): ScopeNode = ScopeNode(this, element.label).also { node = it }

        /**
         * Applies a label update and reports semantics invalidation.
         */
        fun update(
            previous: ScopeElement,
            current: ScopeElement,
            node: ScopeNode,
        ): DirtyMask {
            node.label = current.label
            return if (previous.label == current.label) DirtyMask.None else DirtyMask.of(DirtyPhase.Semantics)
        }

        /**
         * Retains and probes a measurement scope.
         */
        fun checkMeasure(scope: MeasureScope) {
            measureScope = scope
            measureThreadFailures.add(otherThreadFailure { scope.childCount })
            if (throwFromMeasure) {
                throw IllegalStateException("measure failure")
            }
        }

        /**
         * Retains and probes a layout scope.
         */
        fun checkLayout(scope: LayoutScope) {
            layoutScope = scope
            layoutSize = scope.size
            layoutThreadFailures.add(otherThreadFailure { scope.size })
        }

        /**
         * Retains and probes a paint scope.
         */
        fun checkPaint(scope: PaintScope) {
            paintScope = scope
            paintThreadFailures.add(otherThreadFailure { scope.size })
        }

        /**
         * Retains and probes a post-child overlay paint scope.
         */
        fun checkOverlayPaint(scope: PaintScope) {
            overlayPaintScope = scope
            overlayPaintThreadFailures.add(otherThreadFailure { scope.size })
            if (throwFromOverlayPaint) {
                throw IllegalStateException("overlay paint failure")
            }
        }

        /**
         * Retains and probes a semantics scope.
         */
        fun checkSemantics(scope: SemanticsScope) {
            semanticsScope = scope
            semanticsThreadFailures.add(
                otherThreadFailure { scope.emit(Semantics(label = UiText.Literal("wrong-thread"))) },
            )
        }

        /**
         * Invalidates layout on the retained root through its typed test fixture.
         */
        fun invalidateLayoutForTest() {
            val current = node
            check(current != null) { "The scope node has not been created." }
            current.invalidateLayoutForTest()
        }

        private fun otherThreadFailure(action: () -> Any?): Throwable {
            var failure: Throwable? = null
            val thread =
                Thread {
                    failure = runCatching { action() }.exceptionOrNull()
                }
            thread.start()
            thread.join()
            return requireNotNull(failure)
        }

        /**
         * Returns whether the measure callback should invalidate once.
         */
        fun shouldInvalidateMeasure(): Boolean = shouldInvalidate(selfInvalidateMeasure, measureInvalidated) { measureInvalidated = true }

        /**
         * Returns whether the layout callback should invalidate once.
         */
        fun shouldInvalidateLayout(): Boolean = shouldInvalidate(selfInvalidateLayout, layoutInvalidated) { layoutInvalidated = true }

        /**
         * Returns whether the paint callback should invalidate once.
         */
        fun shouldInvalidatePaint(): Boolean = shouldInvalidate(selfInvalidatePaint, paintInvalidated) { paintInvalidated = true }

        /**
         * Returns whether the semantics callback should invalidate once.
         */
        fun shouldInvalidateSemantics(): Boolean = shouldInvalidate(selfInvalidateSemantics, semanticsInvalidated) { semanticsInvalidated = true }

        private fun shouldInvalidate(
            enabled: Boolean,
            alreadyMarked: Boolean,
            mark: () -> Unit,
        ): Boolean {
            if (enabled && alreadyMarked.not()) {
                mark()
                return true
            }
            return false
        }
    }

    private class ParticipationProbe(
        private val invalidateLayoutDuringMeasure: Boolean = false,
    ) {
        var measureCalls: Int = 0
        var layoutCalls: Int = 0
        var paintCalls: Int = 0
        var semanticsCalls: Int = 0
        var measuredChildSizeCalls: Int = 0
        var lastMeasuredChildSize: IntSize = IntSize.Zero
        private var layoutInvalidated: Boolean = false

        /**
         * Records one child measurement and optionally invalidates layout.
         */
        fun markMeasure(node: ChildNode) {
            measureCalls += 1
            if (invalidateLayoutDuringMeasure && layoutInvalidated.not()) {
                layoutInvalidated = true
                node.invalidateForTest()
            }
        }

        /**
         * Records one parent measurement callback.
         */
        fun markParentMeasure() {
            measureCalls += 1
        }

        /**
         * Records one child layout callback.
         */
        fun markLayout() {
            layoutCalls += 1
        }

        /**
         * Records one successful measured-child size read.
         */
        fun markMeasuredChildSize(size: IntSize) {
            measuredChildSizeCalls += 1
            lastMeasuredChildSize = size
        }

        /**
         * Records one child paint callback.
         */
        fun markPaint() {
            paintCalls += 1
        }

        /**
         * Records one child semantics callback.
         */
        fun markSemantics() {
            semanticsCalls += 1
        }
    }

    private class ParentNode(
        private val probe: ParticipationProbe,
        var measureAll: Boolean,
        var placeSecond: Boolean,
        var firstChildOffset: IntOffset,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        SemanticsNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            probe.markParentMeasure()
            if (measureAll) {
                for (index in 0 until scope.childCount) {
                    scope.measureChild(index, constraints)
                }
            } else {
                scope.measureChild(0, constraints)
            }
            return constraints.constrain(IntSize(4, 4))
        }

        override fun layout(scope: LayoutScope) {
            probe.markLayout()
            probe.markMeasuredChildSize(scope.measuredChildSize(0))
            scope.placeChild(0, firstChildOffset)
            if (placeSecond && 1 in 0 until scope.childCount) {
                scope.placeChild(1, IntOffset(0, 2))
            }
        }

        override fun paint(scope: PaintScope) {
            probe.markPaint()
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(0))
        }

        override fun semantics(scope: SemanticsScope) {
            probe.markSemantics()
            scope.emit(Semantics(label = UiText.Literal("parent")))
        }
    }

    private class ChildNode(
        private val probe: ParticipationProbe,
        var size: Int,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        SemanticsNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            probe.markMeasure(this)
            return constraints.constrain(IntSize(size, size))
        }

        override fun layout(scope: LayoutScope) {
            probe.markLayout()
        }

        override fun paint(scope: PaintScope) {
            probe.markPaint()
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(1))
        }

        override fun semantics(scope: SemanticsScope) {
            probe.markSemantics()
            scope.emit(Semantics(label = UiText.Literal("child")))
        }

        /**
         * Invalidates child layout from the measurement callback.
         */
        fun invalidateForTest() {
            invalidate(DirtyMask.of(DirtyPhase.Layout))
        }
    }

    private class ParentElement(
        private val probe: ParticipationProbe,
        val measureAll: Boolean,
        val placeSecond: Boolean,
        val firstChildOffset: IntOffset = IntOffset.Zero,
        children: List<Element>,
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
            children = children,
        ) {
        /**
         * Stable token for the parent fixture.
         */
        companion object {
            val TYPE: ElementType<ParentElement, ParentNode> =
                ElementType(
                    elementClass = ParentElement::class,
                    nodeClass = ParentNode::class,
                    validateLocal = { _ -> },
                    createNode = { element ->
                        ParentNode(element.probe, element.measureAll, element.placeSecond, element.firstChildOffset)
                    },
                    updateNode = { previous, current, node ->
                        node.measureAll = current.measureAll
                        node.placeSecond = current.placeSecond
                        node.firstChildOffset = current.firstChildOffset
                        if (
                            previous.placeSecond == current.placeSecond &&
                            previous.measureAll == current.measureAll &&
                            previous.firstChildOffset == current.firstChildOffset
                        ) {
                            DirtyMask.None
                        } else if (previous.measureAll == current.measureAll) {
                            DirtyMask.of(DirtyPhase.Layout)
                        } else {
                            DirtyMask.of(DirtyPhase.Measure)
                        }
                    },
                )
        }
    }

    private class ChildElement(
        private val probe: ParticipationProbe,
        val size: Int = 2,
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
        ) {
        /**
         * Stable token for the child fixture.
         */
        companion object {
            val TYPE: ElementType<ChildElement, ChildNode> =
                ElementType(
                    elementClass = ChildElement::class,
                    nodeClass = ChildNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> ChildNode(element.probe, element.size) },
                    updateNode = { previous, current, node ->
                        node.size = current.size
                        if (previous.size == current.size) DirtyMask.None else DirtyMask.of(DirtyPhase.Measure)
                    },
                )
        }
    }

    private class ScopeNode(
        private val probe: ScopeProbe,
        var label: UiText,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        OverlayPaintNode,
        SemanticsNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            probe.measureCalls += 1
            probe.checkMeasure(scope)
            if (probe.shouldInvalidateMeasure()) {
                invalidate(DirtyMask.of(DirtyPhase.Measure))
            }
            return constraints.constrain(IntSize(2, 2))
        }

        override fun layout(scope: LayoutScope) {
            probe.layoutCalls += 1
            probe.checkLayout(scope)
            if (probe.shouldInvalidateLayout()) {
                invalidate(DirtyMask.of(DirtyPhase.Layout))
            }
        }

        override fun paint(scope: PaintScope) {
            probe.paintCalls += 1
            probe.checkPaint(scope)
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(0))
            if (probe.shouldInvalidatePaint()) {
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun paintOverlay(scope: PaintScope) {
            probe.overlayPaintCalls += 1
            probe.checkOverlayPaint(scope)
        }

        override fun semantics(scope: SemanticsScope) {
            probe.semanticsCalls += 1
            probe.checkSemantics(scope)
            scope.emit(Semantics(label = label))
            if (probe.shouldInvalidateSemantics()) {
                invalidate(DirtyMask.of(DirtyPhase.Semantics))
            }
        }

        /**
         * Exercises owner-thread validation for node-local invalidation.
         */
        fun invalidatePaintForTest() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        /**
         * Invalidates layout through the bound runtime callback.
         */
        fun invalidateLayoutForTest() {
            invalidate(DirtyMask.of(DirtyPhase.Layout))
        }
    }

    private class ScopeElement(
        val probe: ScopeProbe,
        val label: UiText = UiText.Literal("scope"),
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
        ) {
        /**
         * Stable token for the scope fixture.
         */
        companion object {
            val TYPE: ElementType<ScopeElement, ScopeNode> =
                ElementType(
                    elementClass = ScopeElement::class,
                    nodeClass = ScopeNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> element.probe.create(element) },
                    updateNode = { previous, current, node -> current.probe.update(previous, current, node) },
                )
        }
    }
}
