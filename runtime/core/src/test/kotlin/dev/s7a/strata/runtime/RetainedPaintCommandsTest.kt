package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
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
        pipeline.paint(root)
        val translated = requireNotNull(child.transformedPaint)
        assertNotSame(initial, translated)
        assertEquals(IntRect(3, 2, 7, 6), (translated.beforeChildren.first() as DrawCommand.FillRectangle).bounds)
        assertSame(rootCommands, root.transformedPaint)

        child.localToTree = TreeTransform(0.5, DoubleOffset(3.25, 2.5))
        pipeline.paint(root)
        val fractional = requireNotNull(child.transformedPaint)
        assertNotSame(translated, fractional)
        assertEquals(3.25f, (fractional.beforeChildren.first() as DrawCommand.SampledImage).destination.left)
        assertEquals(5.25f, (fractional.beforeChildren.first() as DrawCommand.SampledImage).destination.right)

        child.localToTree = TreeTransform.Identity
        child.measuredSize = IntSize(8, 6)
        pipeline.paint(root)
        val resized = requireNotNull(child.transformedPaint)
        assertNotSame(fractional, resized)
        assertEquals(DrawCommand.PushClip(IntRect(0, 0, 8, 6)), resized.beforeChildren.last())

        val childNode = child.node as PaintProbe
        childNode.color = ArgbColor(0xFF7195B3.toInt())
        child.dirty += DirtyMask.of(DirtyPhase.Paint)
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
    fun terminalCleanupDropsTransformedCommandsBeforeFailingLifecycleCallbacks() {
        val retained = entry()
        val owner = OwnerGuard()
        val primary = IllegalStateException("dispose")
        val node = retained.node as PaintProbe
        node.onDispose = {
            assertNull(retained.transformedPaint)
            throw primary
        }
        val lifecycle =
            LifecycleManager(NodeOwnershipRegistry(), owner, DirtyTracker()) {
                assertNull(it.transformedPaint)
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
        PaintNode,
        ClipChildrenNode,
        OverlayPaintNode,
        RootOverlayPaintNode,
        LifecycleNode {
        var color: ArgbColor = ArgbColor(0xFF234567.toInt())
        var paintCalls: Int = 0
        var onDispose: () -> Unit = {}

        override fun paint(scope: PaintScope) {
            paintCalls += 1
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
