package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies retained child clipping and post-child overlay ordering.
 */
internal class PaintOrderPipelineTest {
    @Test
    fun parentPaintClipChildrenAndOverlayHaveExactRetainedOrderAndCache() {
        val element = PaintOrderElement()
        val tree = UiTree()
        tree.update(element)
        tree.measure(Constraints(maxWidth = 6, maxHeight = 6))
        tree.layout()

        assertEquals(
            listOf(
                fill(IntRect(0, 0, 4, 4), BACKGROUND),
                DrawCommand.PushClip(IntRect(0, 0, 4, 4)),
                fill(IntRect(-1, -1, 5, 5), CONTENT),
                DrawCommand.PopClip,
                fill(IntRect(0, 0, 1, 1), OVERLAY),
                fill(IntRect(2, 2, 4, 4), ROOT_OVERLAY),
                DrawCommand.SampledImage(ROOT_IMAGE, FloatRect(0f, 0f, 1f, 1f), FloatRect(1f, 1f, 2f, 2f), orientation = SampledImageOrientation.FlipBoth),
            ),
            tree.paint(),
        )
        assertEquals(1, element.node.paintCalls)
        assertEquals(1, element.node.overlayCalls)
        assertEquals(1, element.node.rootOverlayCalls)
        assertEquals(
            InputResult.Ignored,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(4, 4), PointerButton.Primary)),
        )
        assertEquals(0, element.content.node.inputCalls)
        assertEquals(
            InputResult.Consumed,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(3, 3), PointerButton.Primary)),
        )
        assertEquals(1, element.content.node.inputCalls)
        tree.dispatchPointer(PointerEvent.Move(IntOffset(3, 3)))
        tree.dispatchPointer(PointerEvent.Move(IntOffset(4, 4)))
        assertEquals(listOf(true, false), element.content.node.hoverStates)

        tree.paint()
        assertEquals(1, element.node.paintCalls)
        assertEquals(1, element.node.overlayCalls)
        assertEquals(1, element.node.rootOverlayCalls)

        element.node.invalidatePaint()
        tree.paint()
        assertEquals(2, element.node.paintCalls)
        assertEquals(2, element.node.overlayCalls)
        assertEquals(2, element.node.rootOverlayCalls)
        tree.close()
    }

    private fun fill(
        bounds: IntRect,
        color: ArgbColor,
    ): DrawCommand.FillRectangle = DrawCommand.FillRectangle(bounds, color)

    private class PaintOrderElement(
        val content: ContentElement = ContentElement(),
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
            children = listOf(content),
        ) {
        private lateinit var retainedNode: PaintOrderNode

        val node: PaintOrderNode
            get() = retainedNode

        companion object {
            val TYPE: ElementType<PaintOrderElement, PaintOrderNode> =
                ElementType(
                    elementClass = PaintOrderElement::class,
                    nodeClass = PaintOrderNode::class,
                    validateLocal = { },
                    createNode = { element -> PaintOrderNode().also { node -> element.retainedNode = node } },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class PaintOrderNode :
        Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        ClipChildrenNode,
        OverlayPaintNode,
        RootOverlayPaintNode {
        var paintCalls: Int = 0
        var overlayCalls: Int = 0
        var rootOverlayCalls: Int = 0

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, Constraints.fixed(6, 6))
            return constraints.constrain(IntSize(4, 4))
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset(-1, -1))
        }

        override fun paint(scope: PaintScope) {
            paintCalls += 1
            scope.fillRectangle(IntRect(0, 0, 4, 4), BACKGROUND)
        }

        override fun paintOverlay(scope: PaintScope) {
            overlayCalls += 1
            scope.fillRectangle(IntRect(0, 0, 1, 1), OVERLAY)
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            rootOverlayCalls += 1
            assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
            assertEquals(IntSize(4, 4), scope.size)
            scope.fillRectangle(IntRect(2, 2, 4, 4), ROOT_OVERLAY)
            scope.sampledImage(ROOT_IMAGE, FloatRect(0f, 0f, 1f, 1f), FloatRect(1f, 1f, 2f, 2f), SampledImageOrientation.FlipBoth)
        }

        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    private class ContentElement :
        Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
        ) {
        private lateinit var retainedNode: ContentNode

        val node: ContentNode
            get() = retainedNode

        companion object {
            val TYPE: ElementType<ContentElement, ContentNode> =
                ElementType(
                    elementClass = ContentElement::class,
                    nodeClass = ContentNode::class,
                    validateLocal = { },
                    createNode = { element -> ContentNode().also { node -> element.retainedNode = node } },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class ContentNode :
        Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        PointerInputNode,
        PointerHoverNode {
        var inputCalls: Int = 0
        val hoverStates: MutableList<Boolean> = ArrayList()

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(6, 6))

        override fun layout(scope: LayoutScope) = Unit

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, 6, 6), CONTENT)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            inputCalls += 1
            return InputResult.Consumed
        }

        override fun onPointerHover(hovered: Boolean) {
            hoverStates.add(hovered)
        }
    }

    private companion object {
        val BACKGROUND: ArgbColor = ArgbColor(0xFF0000FF.toInt())
        val CONTENT: ArgbColor = ArgbColor(0xFFFF0000.toInt())
        val OVERLAY: ArgbColor = ArgbColor(0xFF00FF00.toInt())
        val ROOT_OVERLAY: ArgbColor = ArgbColor(0xFFFFFF00.toInt())
        val ROOT_IMAGE = createDrawImage(IntSize(1, 1), intArrayOf(-1))
    }
}
