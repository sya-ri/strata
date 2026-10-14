package dev.s7a.strata.runtime.headless

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Checks root-overlay pixels against literal colors, independently of command and backend parity.
 */
internal class HeadlessRootOverlayScopeTest {
    @Test
    fun translatedFractionalAnchorEscapesAncestorClipsAndKeepsNestedPixelOrder() {
        val anchors = ArrayList<IntRect>()
        val leaf =
            OverlayElement(IntSize(4, 6), overlay = { scope ->
                repeat(3) { anchors.add(scope.anchorBounds) }
                assertEquals(IntSize(6, 6), scope.size)
                scope.fillRectangle(IntRect(0, 0, 6, 6), RED)
                scope.withClip(IntRect(0, 1, 5, 5)) {
                    scope.fillRectangle(IntRect(0, 0, 6, 6), GREEN)
                    scope.withClip(IntRect(1, 0, 3, 6)) {
                        scope.fillRectangle(IntRect(0, 0, 6, 6), WHITE)
                    }
                    scope.fillRectangle(IntRect(4, 0, 6, 6), BLUE)
                }
            })
        val parent = OverlayElement(IntSize(4, 4), transform = ChildTransform(0.5, DoubleOffset(0.5, 0.5)), children = listOf(leaf))
        val root = OverlayElement(IntSize(6, 6), offset = IntOffset(2, 3), children = listOf(parent))
        for (scale in 1..4) {
            val frame = renderHeadless(root, IntSize(6, 6), scale)
            val width = 6 * scale
            val expected =
                IntArray(width * width) { index ->
                    val x = index % width / scale
                    val y = index / width / scale
                    when {
                        y < 1 || 5 <= y || 5 <= x -> RED.value
                        1 <= x && x < 3 -> WHITE.value
                        x == 4 -> BLUE.value
                        else -> GREEN.value
                    }
                }
            assertArrayEquals(expected, frame.image.copyArgb(), "scale=$scale")
        }
        assertEquals(List(12) { IntRect(2, 3, 5, 7) }, anchors)
    }

    /**
     * Independent public-node description for transformed child clips and a synchronous overlay callback.
     */
    private class OverlayElement(
        val size: IntSize,
        val offset: IntOffset = IntOffset.Zero,
        val transform: ChildTransform = ChildTransform.Identity,
        val overlay: (RootOverlayPaintScope) -> Unit = { },
        children: List<Element> = emptyList(),
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<OverlayElement, OverlayNode> =
                ElementType(
                    elementClass = OverlayElement::class,
                    nodeClass = OverlayNode::class,
                    validateLocal = { },
                    createNode = { OverlayNode(it) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Draws local blue content beneath the independently asserted root overlays.
     */
    private class OverlayNode(
        private val element: OverlayElement,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ChildTransformNode,
        ClipChildrenNode,
        PaintNode,
        RootOverlayPaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints())
            return constraints.constrain(element.size)
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) scope.placeChild(index, element.offset)
        }

        override fun childTransform(index: Int): ChildTransform = element.transform

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), BLUE)
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            element.overlay(scope)
        }
    }

    private companion object {
        val RED = ArgbColor(0xffff0000.toInt())
        val GREEN = ArgbColor(0xff00ff00.toInt())
        val BLUE = ArgbColor(0xff0000ff.toInt())
        val WHITE = ArgbColor(-1)
    }
}
