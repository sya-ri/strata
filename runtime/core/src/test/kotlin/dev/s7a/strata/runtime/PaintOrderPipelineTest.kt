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
import dev.s7a.strata.node.LifecycleNode
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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies retained child clipping and post-child overlay ordering.
 */
internal class PaintOrderPipelineTest {
    @Test
    fun scopedClipsNestInLocalOrderAndRootOverlaysDelegateWithoutTranslation() {
        val source = FloatRect(0f, 0f, 1f, 1f)
        val destination = FloatRect(-0.25f, 0.5f, 2.75f, 2.5f)
        val probe = ScopedPaintProbe()
        probe.paint = { scope ->
            scope.withClip(IntRect(-1, 0, 5, 4)) {
                assertEquals(IntSize(4, 4), scope.size)
                scope.fillRectangle(IntRect(-2, -2, 6, 6), BACKGROUND)
                scope.withClip(IntRect(0, 1, 2, 3)) {
                    scope.sampledImage(ROOT_IMAGE, source, destination, SampledImageOrientation.FlipBoth)
                }
            }
            scope.fillRectangle(IntRect(0, 0, 1, 1), CONTENT)
        }
        probe.rootOverlay = { scope ->
            assertEquals(IntRect(2, -3, 6, 1), scope.anchorBounds)
            scope.withClip(IntRect(-2, 1, 2, 3)) {
                scope.fillRectangle(IntRect(0, 0, 4, 4), ROOT_OVERLAY)
            }
        }
        val tree = laidOut(ScopedPaintElement(ScopedPaintProbe(), listOf(ScopedPaintElement(probe)), IntOffset(2, -3)))
        val expected =
            listOf(
                DrawCommand.PushClip(IntRect(1, -3, 7, 1)),
                fill(IntRect(0, -5, 8, 3), BACKGROUND),
                DrawCommand.PushClip(IntRect(2, -2, 4, 0)),
                DrawCommand.SampledImage(ROOT_IMAGE, source, destination + IntOffset(2, -3), orientation = SampledImageOrientation.FlipBoth),
                DrawCommand.PopClip,
                DrawCommand.PopClip,
                fill(IntRect(2, -3, 3, -2), CONTENT),
                DrawCommand.PushClip(IntRect(-2, 1, 2, 3)),
                fill(IntRect(0, 0, 4, 4), ROOT_OVERLAY),
                DrawCommand.PopClip,
            )
        assertEquals(expected, tree.paint())
        assertEquals(expected, tree.paint())
        assertEquals(1, probe.paintCalls)
        assertEquals(1, probe.rootOverlayCalls)
        tree.close()
    }

    @Test
    fun emptyAndOutsideClipsPreserveCommandsAndDoNotChangeTheScopeOrigin() {
        val bounds = listOf(IntRect(2, 2, 2, 2), IntRect(10, -20, 12, -18))
        val probe = ScopedPaintProbe()
        var calls = 0
        probe.paint = { scope ->
            bounds.forEach { rectangle ->
                scope.withClip(rectangle) {
                    calls++
                    assertEquals(IntSize(4, 4), scope.size)
                    scope.fillRectangle(IntRect(0, 0, 4, 4), CONTENT)
                }
            }
        }
        val tree = laidOut(ScopedPaintElement(probe))
        val commands = tree.paint()
        assertEquals(2, calls)
        assertEquals(
            bounds.flatMap { rectangle -> listOf(DrawCommand.PushClip(rectangle), fill(IntRect(0, 0, 4, 4), CONTENT), DrawCommand.PopClip) },
            commands,
        )
        assertThrows(IllegalArgumentException::class.java) { IntRect(1, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { IntRect(0, 1, 1, 0) }
        assertThrows(ArithmeticException::class.java) { IntRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1) }
        tree.close()
    }

    @Test
    fun caughtNestedContentFailureRestoresBothClipsAndKeepsTheTreeUsable() {
        val failure = IllegalArgumentException("clipped content")
        val outer = IntRect(-1, -1, 5, 5)
        val inner = IntRect(0, 0, 1, 1)
        val probe = ScopedPaintProbe()
        probe.paint = { scope ->
            val thrown =
                assertThrows(IllegalArgumentException::class.java) {
                    scope.withClip(outer) {
                        scope.withClip(inner) {
                            scope.fillRectangle(inner, CONTENT)
                            throw failure
                        }
                    }
                }
            assertSame(failure, thrown)
            scope.fillRectangle(outer, BACKGROUND)
        }
        val tree = laidOut(ScopedPaintElement(probe))
        assertEquals(
            listOf(DrawCommand.PushClip(outer), DrawCommand.PushClip(inner), fill(inner, CONTENT), DrawCommand.PopClip, DrawCommand.PopClip, fill(outer, BACKGROUND)),
            tree.paint(),
        )
        assertEquals(TreeState.Active, tree.state)
        assertEquals(0, probe.detachCalls)
        tree.close()
    }

    @Test
    fun escapingContentFailurePoisonsTheTreeAndPreservesSuppressedCleanup() {
        val failure = IllegalArgumentException("clipped content")
        val cleanup = IllegalStateException("detach cleanup")
        val probe = ScopedPaintProbe(detachFailure = cleanup)
        var captured: PaintScope? = null
        probe.paint = { scope ->
            captured = scope
            scope.withClip(IntRect(0, 0, 1, 1)) { throw failure }
        }
        val tree = laidOut(ScopedPaintElement(probe))
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { tree.paint() })
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(1, probe.detachCalls)
        assertEquals(1, probe.disposeCalls)
        assertThrows(IllegalStateException::class.java) { requireNotNull(captured).withClip(IntRect(0, 0, 1, 1)) { error("Late callback") } }
        tree.close()
        assertEquals(1, probe.detachCalls)
        assertEquals(1, probe.disposeCalls)
    }

    @Test
    fun scopedClippingChecksThreadAndOwningCallbackLifetimeBeforeInvokingContent() {
        val captured = ArrayList<PaintScope>()
        var calls = 0
        val callback: (PaintScope) -> Unit = { scope ->
            captured.add(scope)
            val failure = AtomicReference<Throwable>()
            val thread =
                Thread {
                    try {
                        scope.withClip(IntRect(0, 0, 1, 1)) { calls++ }
                    } catch (thrown: Throwable) {
                        failure.set(thrown)
                    }
                }
            thread.start()
            thread.join()
            assertTrue(failure.get() is IllegalStateException)
            scope.withClip(IntRect(0, 0, 1, 1)) { calls++ }
        }
        val probe = ScopedPaintProbe()
        probe.paint = callback
        probe.rootOverlay = callback
        val tree = laidOut(ScopedPaintElement(probe))
        tree.paint()
        assertEquals(2, calls)
        captured.forEach { scope ->
            assertThrows(IllegalStateException::class.java) { scope.withClip(IntRect(0, 0, 1, 1)) { calls++ } }
        }
        tree.close()
        captured.forEach { scope ->
            assertThrows(IllegalStateException::class.java) { scope.withClip(IntRect(0, 0, 1, 1)) { calls++ } }
        }
        assertEquals(2, calls)
    }

    @Test
    fun scopedClipTreeTranslationUsesCheckedArithmetic() {
        listOf(
            IntOffset(1, 0) to IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1),
            IntOffset(-1, 0) to IntRect(Int.MIN_VALUE, 0, Int.MIN_VALUE + 1, 1),
        ).forEach { (offset, bounds) ->
            val probe = ScopedPaintProbe()
            probe.paint = { scope -> scope.withClip(bounds) { } }
            val tree = laidOut(ScopedPaintElement(ScopedPaintProbe(), listOf(ScopedPaintElement(probe)), offset))
            assertThrows(ArithmeticException::class.java) { tree.paint() }
            assertEquals(TreeState.Poisoned, tree.state)
            assertEquals(1, probe.detachCalls)
            assertEquals(1, probe.disposeCalls)
            tree.close()
        }
    }

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

    private fun laidOut(element: Element): UiTree =
        UiTree().also { tree ->
            tree.update(element)
            tree.measure(Constraints.fixed(4, 4))
            tree.layout()
        }

    /**
     * Test-owned callbacks and lifecycle observations, never part of a production element.
     */
    private class ScopedPaintProbe(
        val detachFailure: Throwable? = null,
    ) {
        var paint: (PaintScope) -> Unit = { }
        var rootOverlay: (RootOverlayPaintScope) -> Unit = { }
        var paintCalls: Int = 0
        var rootOverlayCalls: Int = 0
        var detachCalls: Int = 0
        var disposeCalls: Int = 0
    }

    /**
     * Immutable description for an independently created scoped-paint fixture node.
     */
    private class ScopedPaintElement(
        val probe: ScopedPaintProbe,
        children: List<Element> = emptyList(),
        val childOffset: IntOffset = IntOffset.Zero,
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<ScopedPaintElement, ScopedPaintNode> =
                ElementType(
                    elementClass = ScopedPaintElement::class,
                    nodeClass = ScopedPaintNode::class,
                    validateLocal = { },
                    createNode = { element -> ScopedPaintNode(element.probe, element.childOffset) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Paint fixture supporting a local callback, a root overlay, and checked child placement.
     */
    private class ScopedPaintNode(
        private val probe: ScopedPaintProbe,
        private val childOffset: IntOffset,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        RootOverlayPaintNode,
        LifecycleNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints.fixed(4, 4))
            return constraints.constrain(IntSize(4, 4))
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) scope.placeChild(index, childOffset)
        }

        override fun paint(scope: PaintScope) {
            probe.paintCalls++
            probe.paint(scope)
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            probe.rootOverlayCalls++
            probe.rootOverlay(scope)
        }

        override fun attach() = Unit

        override fun detach() {
            probe.detachCalls++
            probe.detachFailure?.let { throw it }
        }

        override fun dispose() {
            probe.disposeCalls++
        }
    }

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
