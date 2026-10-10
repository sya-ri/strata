package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent ordered-value and lifetime controls for empty and sparse retained root overlays.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class EmptyRootOverlayTest {
    @Test
    fun absentAndEmptyCallbacksPreserveOrdinaryPaintAndCurrentGeometry() {
        val trace = ArrayList<Event>()
        val child = RootNode(Tag.First, trace)
        val root = ProbeNode(Tag.Root, trace)
        root.offset = IntOffset(-2, -3)
        val tree = laidOut(element(root, listOf(element(child))))
        try {
            val expected = listOf(fill(IntRect(0, 0, 4, 4), ROOT), fill(IntRect(-2, -3, 2, 1), FIRST))
            assertEquals(expected, tree.paint())
            assertEquals(listOf(Event.Paint(Tag.Root), Event.Paint(Tag.First)), trace.filterIsInstance<Event.Paint>())
            assertEquals(listOf(IntSize(4, 4) to IntRect(-2, -3, 2, 1)), child.geometry)
            assertEquals(expected, tree.paint())
            assertEquals(1, child.geometry.size)
            assertFalse(root is RootOverlayPaintNode)
        } finally {
            tree.close()
        }
    }

    @Test
    fun siblingTransitionsKeepChildThenParentOverlaysAndPreviouslyPublishedFrames() {
        val trace = ArrayList<Event>()
        val first = RootNode(Tag.First, trace)
        val second = RootNode(Tag.Second, trace)
        val root = RootNode(Tag.Root, trace)
        second.rootPaint = { it.fillRectangle(SECOND_OVERLAY, SECOND) }
        val session = createRuntimeUiSession { element(root, listOf(element(first), element(second))) }
        session.attach()
        val initial = session.frame(CONSTRAINTS)
        val ordinary = listOf(fill(BOUNDS, ROOT), fill(BOUNDS, FIRST), fill(BOUNDS, SECOND))
        assertEquals(ordinary + fill(SECOND_OVERLAY, SECOND), initial.drawCommands)
        try {
            first.rootPaint = { it.fillRectangle(FIRST_OVERLAY, FIRST) }
            root.rootPaint = { it.fillRectangle(ROOT_OVERLAY, ROOT) }
            first.refreshPaint()
            root.refreshPaint()
            val added = session.frame(CONSTRAINTS)
            val addedValues = ordinary + listOf(fill(FIRST_OVERLAY, FIRST), fill(SECOND_OVERLAY, SECOND), fill(ROOT_OVERLAY, ROOT))
            assertEquals(addedValues, added.drawCommands)
            assertEquals(ordinary + fill(SECOND_OVERLAY, SECOND), initial.drawCommands)
            second.rootPaint = {}
            second.refreshPaint()
            val removed = session.frame(CONSTRAINTS)
            assertEquals(ordinary + listOf(fill(FIRST_OVERLAY, FIRST), fill(ROOT_OVERLAY, ROOT)), removed.drawCommands)
            assertEquals(addedValues, added.drawCommands)
            val calls = trace.toList()
            assertSame(removed, session.frame(CONSTRAINTS))
            assertEquals(calls, trace)
            assertEquals(listOf(Event.Paint(Tag.Root), Event.Paint(Tag.First), Event.Paint(Tag.Second)), trace.filterIsInstance<Event.Paint>().take(3))
        } finally {
            session.close()
        }
        assertEquals(ordinary + fill(SECOND_OVERLAY, SECOND), initial.drawCommands)
        assertEquals(
            listOf(Event.Detach(Tag.Second), Event.Dispose(Tag.Second), Event.Detach(Tag.First), Event.Dispose(Tag.First), Event.Detach(Tag.Root), Event.Dispose(Tag.Root)),
            trace.filter { it is Event.Detach || it is Event.Dispose },
        )
    }

    @Test
    fun unplacedChildrenAreNotPaintedAndZeroChildRootStillEmitsOverlay() {
        val trace = ArrayList<Event>()
        val child = RootNode(Tag.First, trace)
        child.rootPaint = { it.fillRectangle(FIRST_OVERLAY, FIRST) }
        val root = RootNode(Tag.Root, trace)
        root.placeChildren = false
        root.rootPaint = { it.fillRectangle(ROOT_OVERLAY, ROOT) }
        val tree = laidOut(element(root, listOf(element(child))))
        try {
            assertEquals(listOf(fill(BOUNDS, ROOT), fill(ROOT_OVERLAY, ROOT)), tree.paint())
            assertTrue(child.geometry.isEmpty())
            assertEquals(listOf(Event.Paint(Tag.Root)), trace.filterIsInstance<Event.Paint>())
        } finally {
            tree.close()
        }
        val alone = RootNode(Tag.Root, ArrayList())
        alone.rootPaint = { it.fillRectangle(ROOT_OVERLAY, ROOT) }
        val zeroChild = laidOut(element(alone))
        try {
            assertEquals(listOf(fill(BOUNDS, ROOT), fill(ROOT_OVERLAY, ROOT)), zeroChild.paint())
        } finally {
            zeroChild.close()
        }
    }

    @Test
    fun viewportAnchorAndFractionalTransformRefreshRootCommandsAtCurrentBoundary() {
        val trace = ArrayList<Event>()
        val root = ProbeNode(Tag.Root, trace)
        val child = RootNode(Tag.First, trace)
        child.rootPaint = { it.fillRectangle(it.anchorBounds, FIRST) }
        root.offset = IntOffset(-2, -3)
        val tree = laidOut(element(root, listOf(element(child))))
        try {
            assertEquals(fill(IntRect(-2, -3, 2, 1), FIRST), tree.paint().last())
            tree.measure(Constraints.fixed(6, 5))
            tree.layout()
            assertEquals(fill(IntRect(-2, -3, 2, 1), FIRST), tree.paint().last())
            assertEquals(IntSize(6, 5) to IntRect(-2, -3, 2, 1), child.geometry.last())
            root.offset = IntOffset(1, 2)
            root.transform = ChildTransform(0.5, DoubleOffset(0.25, 0.5))
            root.refreshLayout()
            tree.layout()
            val transformed = tree.paint()
            assertEquals(fill(IntRect(1, 2, 4, 5), FIRST), transformed.last())
            assertEquals(IntSize(6, 5) to IntRect(1, 2, 4, 5), child.geometry.last())
            val calls = child.geometry.size
            child.refreshPaint()
            assertEquals(transformed, tree.paint())
            assertEquals(calls + 1, child.geometry.size)
        } finally {
            tree.close()
        }
    }

    @Test
    fun ancestorClipsEndBeforeRootClipsImagesAndSamplingParameters() {
        val image = createDrawImage(IntSize(2, 1), intArrayOf(0xFF123456.toInt(), 0x80112233.toInt()))
        val source = FloatRect(0.25f, 0f, 1.75f, 1f)
        val destination = FloatRect(-0.5f, 1.25f, 3.5f, 2.75f)
        val tint = ArgbColor(0x80402010.toInt())
        val outer = IntRect(-4, -3, 5, 6)
        val inner = IntRect(0, 1, 2, 3)
        val root = ClippedRootNode(Tag.Root, ArrayList())
        val child = RootNode(Tag.First, ArrayList())
        child.rootPaint = { scope ->
            scope.withClip(outer) {
                scope.withClip(inner) {
                    scope.blitImage(image, IntRect(1, 0, 2, 1), FIRST_OVERLAY)
                    scope.sampledImage(image, source, destination, SampledImageOrientation.FlipBoth, tint, 0.25f)
                }
            }
        }
        val tree = laidOut(element(root, listOf(element(child))))
        try {
            assertEquals(
                listOf(
                    fill(BOUNDS, ROOT),
                    DrawCommand.PushClip(BOUNDS),
                    fill(BOUNDS, FIRST),
                    DrawCommand.PopClip,
                    DrawCommand.PushClip(outer),
                    DrawCommand.PushClip(inner),
                    DrawCommand.BlitImage(image, IntRect(1, 0, 2, 1), FIRST_OVERLAY),
                    DrawCommand.SampledImage(image, source, destination, tint, 0.25f, SampledImageOrientation.FlipBoth),
                    DrawCommand.PopClip,
                    DrawCommand.PopClip,
                ),
                tree.paint(),
            )
        } finally {
            tree.close()
        }
    }

    @Test
    fun caughtRootClipFailureRestoresBothClipsAndKeepsTreeUsable() {
        val failure = IllegalArgumentException("nested clip")
        val root = RootNode(Tag.Root, ArrayList())
        root.rootPaint = { scope ->
            assertSame(
                failure,
                assertFailsWith<IllegalArgumentException> {
                    scope.withClip(ROOT_OVERLAY) {
                        scope.withClip(FIRST_OVERLAY) {
                            scope.fillRectangle(SECOND_OVERLAY, SECOND)
                            throw failure
                        }
                    }
                },
            )
            scope.fillRectangle(ROOT_OVERLAY, ROOT)
        }
        val tree = laidOut(element(root))
        try {
            val expected = listOf(fill(BOUNDS, ROOT), DrawCommand.PushClip(ROOT_OVERLAY), DrawCommand.PushClip(FIRST_OVERLAY), fill(SECOND_OVERLAY, SECOND), DrawCommand.PopClip, DrawCommand.PopClip, fill(ROOT_OVERLAY, ROOT))
            assertEquals(expected, tree.paint())
            assertEquals(expected, tree.paint())
            assertEquals(TreeState.Active, tree.state)
        } finally {
            tree.close()
        }
    }

    @Test
    fun everyEscapingPaintStageClosesScopesPreservesPrimaryAndTearsDownOnce() {
        for (stage in FailureStage.entries) {
            val failure = IllegalArgumentException("paint failure")
            val detach = IllegalStateException("detach failure")
            val dispose = IllegalStateException("dispose failure")
            val trace = ArrayList<Event>()
            val root = RootNode(Tag.Root, trace)
            val tree = laidOut(element(root))
            val old = tree.paint()
            val oldValues = old.toList()
            var captured: PaintScope? = null
            val callback: (PaintScope) -> Unit = { scope ->
                captured = scope
                scope.withClip(FIRST_OVERLAY) {
                    scope.fillRectangle(SECOND_OVERLAY, SECOND)
                    throw failure
                }
            }
            when (stage) {
                FailureStage.Paint -> root.localPaint = callback
                FailureStage.LocalOverlay -> root.localOverlay = callback
                FailureStage.RootOverlay -> root.rootPaint = callback
            }
            root.detachFailure = detach
            root.disposeFailure = dispose
            root.refreshPaint()
            assertSame(failure, assertFailsWith<IllegalArgumentException> { tree.paint() })
            assertEquals(listOf(detach, dispose), failure.suppressedExceptions)
            assertEquals(TreeState.Poisoned, tree.state)
            val scope = checkNotNull(captured)
            assertFailsWith<IllegalStateException> { scope.size }
            assertFailsWith<IllegalStateException> { scope.fillRectangle(BOUNDS, ROOT) }
            assertEquals(oldValues, old)
            val cleanup = listOf(Event.Detach(Tag.Root), Event.Dispose(Tag.Root))
            assertEquals(cleanup, trace.filter { it is Event.Detach || it is Event.Dispose })
            tree.close()
            tree.close()
            assertEquals(cleanup, trace.filter { it is Event.Detach || it is Event.Dispose })
            assertEquals(oldValues, old)
        }
    }

    @Test
    fun rootAndLocalScopesRejectDifferentExecutionOwnerAndLateSizeAndWrites() {
        val owner = RuntimeExecutionOwner()
        val other = RuntimeExecutionOwner()
        val escaped = ArrayList<PaintScope>()
        owner.run {
            val root = RootNode(Tag.Root, ArrayList())
            val callback: (PaintScope) -> Unit = { scope ->
                escaped.add(scope)
                other.run {
                    assertFailsWith<IllegalStateException> { scope.size }
                    assertFailsWith<IllegalStateException> { scope.fillRectangle(BOUNDS, ROOT) }
                }
                assertEquals(IntSize(4, 4), scope.size)
            }
            root.localPaint = callback
            root.rootPaint = callback
            val tree = laidOut(element(root))
            tree.paint()
            assertEquals(2, escaped.size)
            escaped.forEach { scope ->
                assertFailsWith<IllegalStateException> { scope.size }
                assertFailsWith<IllegalStateException> { scope.fillRectangle(BOUNDS, ROOT) }
            }
            tree.close()
            escaped.forEach { scope -> assertFailsWith<IllegalStateException> { scope.size } }
        }
    }

    @Test
    fun independentSessionsAndMonitoringPreserveOrderedFramesAndBoundaries() {
        val frames = ArrayList<List<DrawCommand>>()
        for (monitoring in listOf(false, true)) {
            val root = RootNode(Tag.Root, ArrayList())
            val child = RootNode(Tag.First, ArrayList())
            child.rootPaint = { it.fillRectangle(FIRST_OVERLAY, FIRST) }
            val session = createRuntimeUiSession { element(root, listOf(element(child))) }
            session.attach()
            val original = session.frame(CONSTRAINTS)
            val monitor = if (monitoring) session.startRenderMonitoring() else null
            try {
                child.rootPaint = {}
                child.refreshPaint()
                val changed = session.frame(CONSTRAINTS)
                assertEquals(listOf(fill(BOUNDS, ROOT), fill(BOUNDS, FIRST)), changed.drawCommands)
                assertEquals(listOf(fill(BOUNDS, ROOT), fill(BOUNDS, FIRST), fill(FIRST_OVERLAY, FIRST)), original.drawCommands)
                assertSame(changed, session.frame(CONSTRAINTS))
                monitor?.let {
                    val snapshot = it.snapshot()
                    assertFalse(snapshot.overflowed)
                    assertEquals(1L, snapshot.counts[UiRenderMetric.Paint])
                    assertEquals(1L, snapshot.counts[UiRenderMetric.RootOverlayPaint])
                    assertEquals(1L, snapshot.counts[UiRenderMetric.FrameCacheHit])
                    assertEquals(0L, snapshot.counts[UiRenderMetric.Measure])
                    assertEquals(0L, snapshot.counts[UiRenderMetric.Layout])
                }
                frames.add(changed.drawCommands)
            } finally {
                monitor?.close()
                session.close()
            }
            assertEquals(listOf(fill(BOUNDS, ROOT), fill(BOUNDS, FIRST), fill(FIRST_OVERLAY, FIRST)), original.drawCommands)
        }
        assertEquals(frames[0], frames[1])
    }

    @Test
    fun independentOwnersKeepEmptyOverlayFramesReadableAfterReplacementAndClose() {
        val originalFrames =
            List(2) {
                RuntimeExecutionOwner().run {
                    val root = ProbeNode(Tag.Root, ArrayList())
                    val session = createRuntimeUiSession { element(root) }
                    session.attach()
                    val original = session.frame(CONSTRAINTS)
                    try {
                        root.localPaint = { it.fillRectangle(FIRST_OVERLAY, FIRST) }
                        root.refreshPaint()
                        assertEquals(listOf(fill(FIRST_OVERLAY, FIRST)), session.frame(CONSTRAINTS).drawCommands)
                        assertEquals(listOf(fill(BOUNDS, ROOT)), original.drawCommands)
                    } finally {
                        session.close()
                    }
                    assertFailsWith<IllegalStateException> { root.refreshPaint() }
                    original
                }
            }
        RuntimeExecutionOwner().run {
            originalFrames.forEach { assertEquals(listOf(fill(BOUNDS, ROOT)), it.drawCommands) }
            assertEquals(originalFrames[0], originalFrames[1])
        }
    }

    @Test
    fun nonemptyOverflowIsCheckedBeforeReadingAnyCommands() {
        val huge =
            object : AbstractList<DrawCommand>() {
                override val size: Int = Int.MAX_VALUE

                override fun get(index: Int): DrawCommand = error("Overflow must be checked before command access.")
            }
        assertFailsWith<ArithmeticException> { RetainedDrawCommands(listOf(huge, listOf(fill(BOUNDS, ROOT)))) }
    }

    @Test
    fun nestedSparseConcatenationsKeepIndependentCursorsIndexingAndValueEquality() {
        val expected = List(32) { fill(IntRect(it, 0, it + 1, 1), ArgbColor(it)) }
        var nested: List<DrawCommand> = emptyList()
        for (command in expected) nested = RetainedDrawCommands(listOf(emptyList(), nested, listOf(command), emptyList()))
        assertEquals(expected, nested)
        assertEquals(expected.hashCode(), nested.hashCode())
        expected.indices.forEach { assertEquals(expected[it], nested[it]) }
        val first = nested.iterator()
        val second = nested.iterator()
        assertEquals(expected.first(), first.next())
        assertEquals(expected, second.asSequence().toList())
        assertEquals(expected.drop(1), first.asSequence().toList())
        assertFailsWith<NoSuchElementException> { first.next() }
        assertFailsWith<IndexOutOfBoundsException> { nested[-1] }
        assertFailsWith<IndexOutOfBoundsException> { nested[expected.size] }
    }

    private fun laidOut(description: Element): UiTree =
        UiTree().apply {
            update(description)
            measure(CONSTRAINTS)
            layout()
        }

    private fun element(
        node: ProbeNode,
        children: List<Element> = emptyList(),
    ): Element = ProbeElement(node, children)

    private fun fill(
        bounds: IntRect,
        color: ArgbColor,
    ): DrawCommand = DrawCommand.FillRectangle(bounds, color)

    /**
     * Labels independent expected callback and cleanup traces.
     */
    private enum class Tag {
        Root,
        First,
        Second,
    }

    /**
     * Escaping callback stages covered by the shared failure control.
     */
    private enum class FailureStage {
        Paint,
        LocalOverlay,
        RootOverlay,
    }

    /**
     * Records phase and lifecycle attempts without deriving expectations from runtime output.
     */
    private sealed interface Event {
        data class Paint(val tag: Tag) : Event

        data class RootOverlay(val tag: Tag) : Event

        data class Detach(val tag: Tag) : Event

        data class Dispose(val tag: Tag) : Event
    }

    /**
     * Test-owned local paint, geometry and lifecycle behavior without overlay capability.
     */
    private open class ProbeNode(
        val tag: Tag,
        val trace: MutableList<Event>,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        ChildTransformNode,
        LifecycleNode {
        var offset = IntOffset.Zero
        var transform = ChildTransform.Identity
        var placeChildren = true
        var localPaint: (PaintScope) -> Unit = { it.fillRectangle(BOUNDS, color()) }
        var detachFailure: Throwable? = null
        var disposeFailure: Throwable? = null

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints.fixed(4, 4))
            return constraints.constrain(IntSize(4, 4))
        }

        override fun layout(scope: LayoutScope) {
            if (placeChildren) {
                for (index in 0 until scope.childCount) scope.placeChild(index, offset)
            }
        }

        override fun childTransform(index: Int): ChildTransform = transform

        override fun paint(scope: PaintScope) {
            trace.add(Event.Paint(tag))
            localPaint(scope)
        }

        override fun attach() = Unit

        override fun detach() {
            trace.add(Event.Detach(tag))
            detachFailure?.let { throw it }
        }

        override fun dispose() {
            trace.add(Event.Dispose(tag))
            disposeFailure?.let { throw it }
        }

        /**
         * Invalidates local paint so every applicable overlay callback refreshes.
         */
        fun refreshPaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        /**
         * Replaces child placement and transform without changing measured size.
         */
        fun refreshLayout() {
            invalidate(DirtyMask.of(DirtyPhase.Layout))
        }

        private fun color(): ArgbColor =
            when (tag) {
                Tag.Root -> ROOT
                Tag.First -> FIRST
                Tag.Second -> SECOND
            }
    }

    /**
     * Keeps both overlay callbacks applicable even when their command lists are empty.
     */
    private open class RootNode(
        tag: Tag,
        trace: MutableList<Event>,
    ) : ProbeNode(tag, trace),
        OverlayPaintNode,
        RootOverlayPaintNode {
        val geometry = ArrayList<Pair<IntSize, IntRect>>()
        var localOverlay: (PaintScope) -> Unit = {}
        var rootPaint: (RootOverlayPaintScope) -> Unit = {}

        override fun paintOverlay(scope: PaintScope) = localOverlay(scope)

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            trace.add(Event.RootOverlay(tag))
            geometry.add(scope.size to scope.anchorBounds)
            rootPaint(scope)
        }
    }

    /**
     * Provides the existing ancestor child-clip capability for the clip escape control.
     */
    private class ClippedRootNode(
        tag: Tag,
        trace: MutableList<Event>,
    ) : RootNode(tag, trace),
        ClipChildrenNode

    /**
     * Stable typed description owning one fresh probe per retained lifetime.
     */
    private class ProbeElement(
        val node: ProbeNode,
        children: List<Element>,
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<ProbeElement, ProbeNode> =
                ElementType(ProbeElement::class, ProbeNode::class, validateLocal = {}, createNode = { it.node }, updateNode = { _, _, _ -> DirtyMask.None })
        }
    }

    private companion object {
        val CONSTRAINTS = Constraints.fixed(4, 4)
        val BOUNDS = IntRect(0, 0, 4, 4)
        val ROOT_OVERLAY = IntRect(7, 8, 9, 10)
        val FIRST_OVERLAY = IntRect(-3, 2, -1, 3)
        val SECOND_OVERLAY = IntRect(5, -2, 6, -1)
        val ROOT = ArgbColor(0xFF123456.toInt())
        val FIRST = ArgbColor(0xFFABCDEF.toInt())
        val SECOND = ArgbColor(0xFF654321.toInt())
    }
}
