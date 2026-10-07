package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies current-entry transformed command reuse, geometry changes and terminal release.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedPaintCommandsTest {
    @Test
    fun paintOnlyLayoutReusesGeometryButAncestorTransformsAndMeasuredSizesStillPropagate() {
        val root = entry()
        val child = entry()
        val descendant = entry()
        for ((parent, nested) in listOf(root to child, child to descendant)) {
            parent.children.add(nested)
            nested.parent = parent
            parent.measuredChildren = setOf(0)
            parent.placements[0] = IntOffset.Zero
        }
        for (retained in listOf(root, child, descendant)) {
            retained.laidOut = true
            retained.dirty = DirtyMask.None
        }
        val pipeline = Pipeline(OwnerGuard())
        pipeline.layout(root)
        val bounds = descendant.bounds
        val transform = descendant.localToTree
        descendant.dirty += DirtyMask.of(DirtyPhase.Paint)
        pipeline.layout(root)
        assertSame(bounds, descendant.bounds)
        assertSame(transform, descendant.localToTree)

        (root.node as PaintProbe).transform = ChildTransform(0.5, DoubleOffset(3.25, 2.5))
        root.dirty += DirtyMask.of(DirtyPhase.Layout)
        pipeline.layout(root)
        assertEquals(IntRect(3, 2, 6, 5), descendant.bounds)
        assertEquals(TreeTransform(0.5, DoubleOffset(3.25, 2.5)), descendant.localToTree)

        child.measuredSize = IntSize(8, 6)
        child.dirty += DirtyMask.of(DirtyPhase.Layout)
        pipeline.layout(root)
        assertEquals(IntRect(3, 2, 8, 6), child.bounds)
        assertEquals(IntRect(3, 2, 6, 5), descendant.bounds)
    }

    @Test
    fun cleanEntriesReuseTheirCommandsWhileEveryLocalInputReplacesOnlyCurrentState() {
        val root = entry()
        val child = entry()
        root.children.add(child)
        child.parent = root
        val pipeline = PaintPipeline(OwnerGuard())
        val first = pipeline.paint(root)
        val rootCommands = requireNotNull(root.transformedPaint)
        val initial = requireNotNull(child.transformedPaint)
        val repeated = pipeline.paint(root)
        first.zip(repeated).forEach { (before, after) -> assertSame(before, after) }
        assertSame(initial, child.transformedPaint)

        child.localToTree = TreeTransform(1.0, DoubleOffset(3.0, 2.0))
        root.paintSubtreeDirty = true
        pipeline.paint(root)
        val translated = requireNotNull(child.transformedPaint)
        assertNotSame(initial, translated)
        assertEquals(IntRect(3, 2, 7, 6), (translated.beforeChildren.first() as DrawCommand.FillRectangle).bounds)
        assertSame(rootCommands, root.transformedPaint)

        child.localToTree = TreeTransform(0.5, DoubleOffset(3.25, 2.5))
        root.paintSubtreeDirty = true
        pipeline.paint(root)
        val fractional = requireNotNull(child.transformedPaint)
        assertNotSame(translated, fractional)
        assertEquals(3.25f, (fractional.beforeChildren.first() as DrawCommand.SampledImage).destination.left)
        assertEquals(5.25f, (fractional.beforeChildren.first() as DrawCommand.SampledImage).destination.right)

        child.localToTree = TreeTransform.Identity
        child.measuredSize = IntSize(8, 6)
        root.paintSubtreeDirty = true
        pipeline.paint(root)
        val resized = requireNotNull(child.transformedPaint)
        assertNotSame(fractional, resized)
        assertEquals(DrawCommand.PushClip(IntRect(0, 0, 8, 6)), resized.beforeChildren.last())

        val childNode = child.node as PaintProbe
        childNode.color = ArgbColor(0xFF7195B3.toInt())
        child.dirty += DirtyMask.of(DirtyPhase.Paint)
        root.paintSubtreeDirty = true
        pipeline.paint(root)
        val repainted = requireNotNull(child.transformedPaint)
        assertNotSame(resized, repainted)
        assertEquals(ArgbColor(0xFF7195B3.toInt()), (repainted.beforeChildren.first() as DrawCommand.FillRectangle).color)
        assertSame(rootCommands, root.transformedPaint)

        root.measuredSize = IntSize(40, 10)
        pipeline.paint(root)
        val newViewport = requireNotNull(child.transformedPaint)
        assertNotSame(repainted, newViewport)
        assertEquals(IntRect(0, 0, 40, 1), (newViewport.rootOverlays.single() as DrawCommand.FillRectangle).bounds)
        assertEquals(2, childNode.paintCalls)
        assertEquals(1, (root.node as PaintProbe).paintCalls)
    }

    @Test
    fun changedGrandchildSharesTheOtherBranchAndPreservesPreviouslyPublishedCommands() {
        val root = entry()
        val branch = entry()
        val leaf = entry()
        val sibling = entry()
        root.children.addAll(listOf(branch, sibling))
        branch.parent = root
        sibling.parent = root
        branch.children.add(leaf)
        leaf.parent = branch
        val probe = leaf.node as PaintProbe
        probe.color = ArgbColor(0xFF112233.toInt())
        val pipeline = PaintPipeline(OwnerGuard())
        val before = pipeline.paint(root)
        val detached = before.toList()
        val unchanged = requireNotNull(sibling.paintSnapshot)
        val previousBranch = requireNotNull(branch.paintSnapshot)
        val oldColor = probe.color
        probe.color = ArgbColor(0xFF778899.toInt())
        DirtyTracker().record(leaf, DirtyMask.of(DirtyPhase.Paint))
        val after = pipeline.paint(root)
        assertSame(unchanged, sibling.paintSnapshot)
        assertNotSame(previousBranch, branch.paintSnapshot)
        assertEquals(detached, before)
        assertEquals(
            detached.map { if (it is DrawCommand.FillRectangle && it.color == oldColor) it.copy(color = probe.color) else it },
            after,
        )
        assertEquals(2, probe.paintCalls)
        assertEquals(1, (sibling.node as PaintProbe).paintCalls)
    }

    @Test
    fun reorderingAndUnplacingCachedChildrenRefreshBothOrdinaryAndRootOverlayOrder() {
        val root = entry()
        val first = entry()
        val second = entry()
        val firstColor = ArgbColor(0xFF112233.toInt())
        val secondColor = ArgbColor(0xFF445566.toInt())
        (first.node as PaintProbe).color = firstColor
        (second.node as PaintProbe).color = secondColor
        root.children.addAll(listOf(first, second))
        first.parent = root
        second.parent = root
        val tracker = DirtyTracker()
        val pipeline = PaintPipeline(OwnerGuard())
        val published = pipeline.paint(root)
        val originalFirst = first.paintSnapshot
        root.children.reverse()
        tracker.structural(root)
        val reordered = pipeline.paint(root)
        assertSame(originalFirst, first.paintSnapshot)
        assertEquals(firstColor, (published[2] as DrawCommand.FillRectangle).color)
        assertEquals(secondColor, (reordered[2] as DrawCommand.FillRectangle).color)
        assertEquals(firstColor, (reordered[6] as DrawCommand.FillRectangle).color)
        assertEquals(listOf(secondColor, firstColor, (root.node as PaintProbe).color), reordered.takeLast(3).map { (it as DrawCommand.FillRectangle).color })
        first.placed = false
        tracker.record(root, DirtyMask.of(DirtyPhase.Layout))
        val unplaced = pipeline.paint(root)
        assertEquals(10, unplaced.size)
        assertEquals(listOf(secondColor, root.node.color), unplaced.takeLast(2).map { (it as DrawCommand.FillRectangle).color })
        assertEquals(15, published.size)
        assertEquals(1, first.node.paintCalls)
    }

    @Test
    fun invalidationFromPaintRemainsPendingAndRemovalDropsAncestorSnapshotsBeforeDispose() {
        val root = entry()
        val child = entry()
        root.children.add(child)
        child.parent = root
        val owner = OwnerGuard()
        val tracker = DirtyTracker()
        val lifecycle = LifecycleManager(NodeOwnershipRegistry(), owner, tracker) {}
        for (retained in listOf(root, child)) {
            lifecycle.bind(retained)
            lifecycle.attachCurrent(retained)
        }
        val probe = child.node as PaintProbe
        probe.onPaint = {
            if (probe.paintCalls == 1) tracker.record(child, DirtyMask.of(DirtyPhase.Paint))
        }
        val pipeline = PaintPipeline(owner)
        val published = pipeline.paint(root)
        val detached = published.toList()
        pipeline.paint(root)
        assertEquals(2, probe.paintCalls)
        probe.onDispose = {
            assertNull(root.paintSnapshot)
            assertNull(child.paintSnapshot)
        }
        assertNull(lifecycle.cleanup(child))
        root.children.clear()
        tracker.structural(root)
        val afterRemoval = pipeline.paint(root)
        assertEquals(5, afterRemoval.size)
        assertEquals(detached, published)
        assertNull(lifecycle.cleanup(root))
    }

    @Test
    fun terminalCleanupDropsTransformedCommandsBeforeFailingLifecycleCallbacks() {
        val retained = entry()
        val owner = OwnerGuard()
        val primary = IllegalStateException("dispose")
        val node = retained.node as PaintProbe
        node.onDispose = {
            assertNull(retained.transformedPaint)
            assertNull(retained.paintSnapshot)
            throw primary
        }
        val lifecycle =
            LifecycleManager(NodeOwnershipRegistry(), owner, DirtyTracker()) {
                assertNull(it.transformedPaint)
                assertNull(it.paintSnapshot)
            }
        lifecycle.bind(retained)
        lifecycle.attachCurrent(retained)
        PaintPipeline(owner).paint(retained)
        requireNotNull(retained.transformedPaint)
        assertSame(primary, lifecycle.cleanup(retained))
        assertNull(retained.transformedPaint)
    }

    private fun entry(): RetainedNode {
        val node = PaintProbe()
        return RetainedNode(PaintElement(node), node, null).apply {
            measuredSize = IntSize(4, 4)
            bounds = IntRect(0, 0, 4, 4)
            placed = true
        }
    }

    /**
     * Supplies a stable valid description for the directly exercised retained entry.
     */
    private class PaintElement(
        val node: PaintProbe,
    ) : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<PaintElement, PaintProbe> =
                ElementType(PaintElement::class, PaintProbe::class, validateLocal = {}, createNode = { it.node }, updateNode = { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Emits local, clipped overlay and viewport-dependent root paint through the real collectors.
     */
    private class PaintProbe :
        Node(),
        LayoutNode,
        ChildTransformNode,
        PaintNode,
        ClipChildrenNode,
        OverlayPaintNode,
        RootOverlayPaintNode,
        LifecycleNode {
        var color: ArgbColor = ArgbColor(0xFF234567.toInt())
        var paintCalls: Int = 0
        var onDispose: () -> Unit = {}
        var onPaint: () -> Unit = {}
        var transform: ChildTransform = ChildTransform.Identity

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) scope.placeChild(index, IntOffset.Zero)
        }

        override fun childTransform(index: Int): ChildTransform = transform

        override fun paint(scope: PaintScope) {
            paintCalls += 1
            onPaint()
            scope.fillRectangle(IntRect(0, 0, 4, 4), color)
        }

        override fun paintOverlay(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, 1, 1), color)
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, 1), color)
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() = onDispose()
    }
}
