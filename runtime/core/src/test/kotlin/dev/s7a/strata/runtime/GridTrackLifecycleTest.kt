@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies externally reachable Grid storage through actual core failure and terminal lifecycle paths.
 */
internal class GridTrackLifecycleTest {
    @Test
    fun transientSessionDetachPreservesCurrentTracksAndCloseReleasesThem() {
        val session = createRuntimeUiSession { grid(listOf(Leaf())) }
        session.attach()
        session.frame(Constraints())
        val owner = session.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(session)
        val tree = owner.javaClass.getDeclaredField("tree").apply { isAccessible = true }.get(owner) as UiTree
        val node = rootNode(tree)
        val before = tracks(node)
        session.detach()
        tracks(node).zip(before).forEach { (a, b) -> assertSame(a, b) }
        session.attach()
        session.frame(Constraints())
        tracks(node).forEach { assertNotNull(it) }
        session.close()
        assertEquals(listOf(null, null), tracks(node))
        session.close()
    }

    @Test
    fun measurementAndLayoutFailuresClearPreviouslySuccessfulTracks() {
        for (phase in listOf(FailurePhase.Measure, FailurePhase.Layout)) {
            val leaf = Leaf()
            val tree = primed(leaf)
            val node = rootNode(tree)
            leaf.failurePhase = phase
            leaf.requestGeometry()
            val failure = assertThrows<IllegalStateException> {
                tree.measure(Constraints())
                tree.layout()
            }
            assertSame(leaf.failure, failure)
            assertEquals(listOf(null, null), tracks(node))
            tree.close()
        }
    }

    @Test
    fun failedChildAttachmentClearsTheReachableExistingGrid() {
        val leaf = Leaf()
        val tree = primed(leaf)
        val node = rootNode(tree)
        val inserted = Leaf().apply { failurePhase = FailurePhase.Attach }
        assertSame(inserted.failure, assertThrows<IllegalStateException> { tree.update(grid(listOf(leaf, inserted))) })
        assertEquals(listOf(null, null), tracks(node))
        tree.close()
    }

    @Test
    fun throwingDescendantCleanupStillReleasesTracksAndDoesNotRepeatCallbacks() {
        for (phase in listOf(FailurePhase.Detach, FailurePhase.Dispose)) {
            val leaf = Leaf()
            val tree = primed(leaf)
            val node = rootNode(tree)
            leaf.failurePhase = phase
            assertSame(leaf.failure, assertThrows<IllegalStateException> { tree.close() })
            assertEquals(listOf(null, null), tracks(node))
            assertEquals(1, leaf.detaches)
            assertEquals(1, leaf.disposals)
            tree.close()
            assertEquals(1, leaf.detaches)
            assertEquals(1, leaf.disposals)
        }
    }

    @Test
    fun terminalRootReplacementReleasesTracksWhileTheOldNodeRemainsReachable() {
        val tree = primed(Leaf())
        val node = rootNode(tree)
        tree.update(evaluateComponentTree { Spacer() })
        assertEquals(listOf(null, null), tracks(node))
        tree.close()
    }

    @Test
    fun checkedNaturalExtentFailureClearsTracksThroughTheCoreBoundary() {
        val first = Leaf()
        val tree = primed(first, Leaf())
        val node = rootNode(tree)
        first.size = IntSize(Int.MAX_VALUE, 1)
        first.requestGeometry()
        assertThrows<ArithmeticException> { tree.measure(Constraints()) }
        assertEquals(listOf(null, null), tracks(node))
        tree.close()
    }

    @Test
    fun checkedAccumulatedPlacementOverflowClearsTheMeasuredDescendantGrid() {
        val tree = UiTree()
        tree.update(OffsetElement(grid(listOf(Leaf()))))
        tree.measure(Constraints())
        val retained = tree.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(tree) as RetainedNode
        val node = retained.children.single().node
        tracks(node).forEach { assertNotNull(it) }
        assertThrows<ArithmeticException> { tree.layout() }
        assertEquals(listOf(null, null), tracks(node))
        tree.close()
    }

    private fun primed(vararg leaves: Leaf): UiTree = UiTree().also { tree ->
        tree.update(grid(leaves.toList()))
        tree.measure(Constraints())
        tree.layout()
    }

    private fun grid(leaves: List<Leaf>): Element = evaluateComponentTree {
        Grid(columns = 2) { leaves.forEach { element(LeafElement(it)) } }
    }

    private fun rootNode(tree: UiTree): Node =
        (tree.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(tree) as RetainedNode).node

    private fun tracks(node: Node): List<IntArray?> =
        listOf("columnWidths", "rowHeights").map { name ->
            node.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(node) as IntArray?
        }

    /**
     * Callback boundary selected independently from Grid's implementation.
     */
    private enum class FailurePhase { None, Attach, Measure, Layout, Detach, Dispose }

    /**
     * Retained child able to throw at each ordinary lifecycle or geometry boundary.
     */
    private class Leaf : Node(), MeasureNode, LayoutNode, LifecycleNode {
        var failurePhase = FailurePhase.None
        val failure = IllegalStateException("child callback")
        var size = IntSize(3, 2)
        var detaches = 0
        var disposals = 0

        override fun measure(scope: MeasureScope, constraints: Constraints): IntSize {
            fail(FailurePhase.Measure)
            return constraints.constrain(size)
        }

        override fun layout(scope: LayoutScope) = fail(FailurePhase.Layout)
        override fun attach() = fail(FailurePhase.Attach)
        override fun detach() {
            detaches += 1
            fail(FailurePhase.Detach)
        }

        override fun dispose() {
            disposals += 1
            fail(FailurePhase.Dispose)
        }

        /**
         * Requests actual ancestor measurement and child layout before the next core pass.
         */
        fun requestGeometry() = invalidate(DirtyMask.of(DirtyPhase.Measure))

        private fun fail(phase: FailurePhase) {
            if (failurePhase == phase) throw failure
        }
    }

    /**
     * A parent placing its measured Grid beyond the representable accumulated right edge.
     */
    private class OffsetNode : Node(), MeasureNode, LayoutNode {
        override fun measure(scope: MeasureScope, constraints: Constraints): IntSize {
            scope.measureChild(0, Constraints())
            return constraints.constrain(IntSize.Zero)
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset(Int.MAX_VALUE, 0))
        }
    }

    /**
     * Public-SPI parent description isolating placement overflow from natural extent calculation.
     */
    private class OffsetElement(child: Element) : Element(ElementIdentity.Positional, TYPE, children = listOf(child)) {
        companion object {
            val TYPE: ElementType<OffsetElement, OffsetNode> = ElementType(
                elementClass = OffsetElement::class,
                nodeClass = OffsetNode::class,
                validateLocal = {},
                createNode = { OffsetNode() },
                updateNode = { _, _, _ -> DirtyMask.None },
            )
        }
    }

    /**
     * Static public-SPI child declaration with a test-owned node surviving failure.
     */
    private class LeafElement(val leaf: Leaf) : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<LeafElement, Leaf> = ElementType(
                elementClass = LeafElement::class,
                nodeClass = Leaf::class,
                validateLocal = {},
                createNode = { it.leaf },
                updateNode = { _, _, _ -> DirtyMask.None },
            )
        }
    }
}
