package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Compares pointer-capability enumeration with the independent original recursive dispatcher on JVM and JavaScript.
 * Synthetic committed geometry isolates callback order, clips, overflow, capture and index replacement from layout algorithms.
 */
internal class PortablePointerCapabilityTest {
    @Test
    fun broadSparseAndDenseTreesPreserveHighRateCallbackOrderAndConsumption() {
        for (count in listOf(0, 32, 1000)) {
            for (step in listOf(0, 1, 10)) {
                val participants = if (step == 0) emptyList() else (0 until count).filter { id -> id % step == 0 }
                for (stop in listOf(-1, participants.firstOrNull() ?: -1, participants.lastOrNull() ?: -1).distinct()) {
                    parity({ broad(count, step, stop) }, List(100) { index -> PointerEvent.Move(if (index % 2 == 0) inside else outside) })
                }
            }
        }
        val scene = broad(5, 2)
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(scene.root)
        assertEquals(InputResult.Ignored, pipeline.dispatch(scene.root, PointerEvent.Move(inside)))
        assertEquals(
            listOf<Trace>(Trace.Hover(4, true), Trace.Hover(2, true), Trace.Hover(0, true), Trace.Input(4, PointerEvent.Move(inside), inside), Trace.Input(2, PointerEvent.Move(inside), inside), Trace.Input(0, PointerEvent.Move(inside), inside)),
            scene.trace,
        )
    }

    @Test
    fun hoverOnlyAndNonCapturingInputCapabilitiesPreserveTheirSeparateProtocols() {
        val trace = ArrayList<Trace>()
        val root = retained(Passive())
        val input = retained(InputOnly(trace), root)
        root.children += input
        root.children += retained(HoverOnly(trace), root)
        root.children += retained(Participant(2, trace), root)
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(root)
        val move = PointerEvent.Move(inside)
        assertEquals(InputResult.Ignored, pipeline.dispatch(root, move))
        assertEquals(listOf<Trace>(Trace.Hover(2, true), Trace.Hover(1, true), Trace.Input(2, move, inside), Trace.Input(0, move, inside)), trace)
        trace.clear()
        val press = PointerEvent.Press(inside, PointerButton.Primary)
        assertEquals(InputResult.Consumed, pipeline.dispatch(root, press))
        assertEquals(listOf<Trace>(Trace.Input(2, press, inside), Trace.Input(0, press, inside)), trace)
        trace.clear()
        assertEquals(InputResult.Ignored, pipeline.dispatch(root, PointerEvent.Drag(outside, PointerButton.Primary, 1.0, 1.0)))
        assertEquals(listOf<Trace>(Trace.Hover(2, false), Trace.Hover(1, false)), trace)
    }

    @Test
    fun deepInertAndCapableAncestryPreservesDeepestFirstHoverAndInput() {
        for (depth in listOf(32, 128)) {
            for (dense in listOf(false, true)) {
                parity({ deep(depth, dense) }, List(100) { PointerEvent.Move(inside) })
            }
        }
    }

    @Test
    fun clipsDoNotPruneOverflowThroughUnclippedParentsAndCaptureUsesCurrentTransforms() {
        for (clipped in listOf(false, true)) {
            val events =
                listOf(
                    PointerEvent.Move(IntOffset(14, 16)),
                    PointerEvent.Press(IntOffset(6, 8), PointerButton.Primary),
                    PointerEvent.Drag(IntOffset(50, 60), PointerButton.Primary, 12.0, 14.0),
                    PointerEvent.Release(IntOffset(50, 60), PointerButton.Primary),
                )
            parity({ transformed(clipped) }, events)
            val scene = transformed(clipped)
            val pipeline = InputPipeline(FocusedInputPipeline())
            pipeline.layoutCommitted(scene.root)
            assertEquals(if (clipped) InputResult.Ignored else InputResult.Consumed, pipeline.dispatch(scene.root, events[0]))
            pipeline.dispatch(scene.root, events[1])
            scene.trace.clear()
            pipeline.dispatch(scene.root, events[2])
            assertEquals(
                Trace.Input(0, PointerEvent.Drag(IntOffset(50, 60), PointerButton.Primary, 6.0, 7.0), IntOffset(23, 27)),
                scene.trace.last(),
            )
            scene.trace.clear()
            val leaf = scene.participants.single()
            leaf.localToTree = TreeTransform(3.0, DoubleOffset(6.0, 9.0))
            pipeline.layoutCommitted(scene.root)
            pipeline.dispatch(scene.root, events[3])
            assertEquals(Trace.Input(0, events[3], IntOffset(14, 17)), scene.trace.single())
        }
    }

    @Test
    fun unplacingAnAncestorCancelsCaptureAndResetVisitsRetainedUnplacedHover() {
        val actual = deep(4, false, consume = true)
        val expected = deep(4, false, consume = true)
        val pipeline = InputPipeline(FocusedInputPipeline())
        val oracle = OriginalPointer(expected.root)
        pipeline.layoutCommitted(actual.root)
        oracle.layoutCommitted()
        val press = PointerEvent.Press(inside, PointerButton.Primary)
        assertEquals(oracle.dispatch(press), pipeline.dispatch(actual.root, press))
        actual.trace.clear()
        expected.trace.clear()
        actual.root.children.single().placed = false
        expected.root.children.single().placed = false
        pipeline.layoutCommitted(actual.root)
        oracle.layoutCommitted()
        assertEquals(listOf<Trace>(Trace.Cancelled(4, PointerButton.Primary)), actual.trace)
        assertEquals(expected.trace, actual.trace)
        actual.trace.clear()
        expected.trace.clear()
        assertEquals(oracle.dispatch(PointerEvent.Move(inside)), pipeline.dispatch(actual.root, PointerEvent.Move(inside)))
        assertEquals(emptyList(), actual.trace)
        pipeline.clearHover(actual.root)
        oracle.clearHover()
        assertEquals(listOf<Trace>(Trace.Hover(4, false)), actual.trace)
        assertEquals(expected.trace, actual.trace)
        pipeline.cancelCapture()
        assertEquals(listOf<Trace>(Trace.Hover(4, false)), actual.trace)
    }

    @Test
    fun repeatedLayoutRootReplacementAndOwnerIsolationDoNotKeepOldParticipants() {
        val first = broad(8, 2)
        val second = broad(3, 1)
        val firstPipeline = InputPipeline(FocusedInputPipeline())
        val secondPipeline = InputPipeline(FocusedInputPipeline())
        firstPipeline.layoutCommitted(first.root)
        secondPipeline.layoutCommitted(second.root)
        repeat(100) { firstPipeline.dispatch(first.root, PointerEvent.Move(inside)) }
        assertEquals(800, first.trace.size)
        assertEquals(emptyList(), second.trace)
        first.trace.clear()
        secondPipeline.dispatch(second.root, PointerEvent.Move(inside))
        assertEquals(emptyList(), first.trace)
        assertEquals(6, second.trace.size)
        second.trace.clear()
        firstPipeline.entryWillCleanup(first.participants.first())
        firstPipeline.dispatch(second.root, PointerEvent.Move(inside))
        assertEquals(emptyList(), first.trace)
        assertEquals(6, second.trace.size)
        second.trace.clear()
        second.root.children.removeAt(2)
        firstPipeline.layoutCommitted(second.root)
        firstPipeline.dispatch(second.root, PointerEvent.Move(inside))
        assertEquals(listOf<Trace>(Trace.Hover(1, true), Trace.Hover(0, true), Trace.Input(1, PointerEvent.Move(inside), inside), Trace.Input(0, PointerEvent.Move(inside), inside)), second.trace)
    }

    @Test
    fun inputInvalidationKeepsCommittedGeometryUntilTheNextLayoutBoundary() {
        val scene = broad(1, 1)
        val leaf = scene.participants.single()
        val node = leaf.node as Participant
        node.onInput = { leaf.dirty += DirtyMask.of(DirtyPhase.Layout) }
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(scene.root)
        repeat(2) {
            pipeline.dispatch(scene.root, PointerEvent.Move(inside))
            assertEquals(Trace.Input(0, PointerEvent.Move(inside), inside), scene.trace.last())
        }
        leaf.localToTree = TreeTransform(1.0, DoubleOffset(5.0, 0.0))
        pipeline.layoutCommitted(scene.root)
        scene.trace.clear()
        assertEquals(InputResult.Ignored, pipeline.dispatch(scene.root, PointerEvent.Move(inside)))
        assertEquals(listOf<Trace>(Trace.Hover(0, false)), scene.trace)
    }

    @Test
    fun callbackFailuresKeepTheirIdentityAndCancellationIsAttemptedOnlyOnce() {
        val scene = broad(1, 1, stop = 0)
        val node = scene.participants.single().node as Participant
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(scene.root)
        pipeline.dispatch(scene.root, PointerEvent.Press(inside, PointerButton.Primary))
        val failure = IllegalArgumentException("Rejected pointer hover")
        node.hoverFailure = failure
        assertSame(failure, assertFailsWith<IllegalArgumentException> { pipeline.dispatch(scene.root, PointerEvent.Drag(outside, PointerButton.Primary, 1.0, 1.0)) })
        node.hoverFailure = null
        val cancellation = IllegalStateException("Rejected capture cancellation")
        node.cancelFailure = cancellation
        assertSame(cancellation, assertFailsWith<IllegalStateException> { pipeline.cancelCapture() })
        val trace = scene.trace.toList()
        pipeline.cancelCapture()
        assertEquals(trace, scene.trace)
        assertEquals(1, scene.trace.count { it is Trace.Cancelled })
    }

    @Test
    fun nestedClipsKeepExitOrderWhileOverflowThroughInertParentsStillHits() {
        val trace = ArrayList<Trace>()
        val root = retained(Clip())
        val passive = retained(Passive(), root).also { it.measuredSize = IntSize(1, 1) }
        root.children += passive
        val clip = retained(Clip(), passive).also {
            it.measuredSize = IntSize(5, 5)
            it.localToTree = TreeTransform(1.0, DoubleOffset(2.0, 2.0))
        }
        passive.children += clip
        val leaf = retained(Participant(0, trace, consume = true), clip).also {
            it.localToTree = TreeTransform(1.0, DoubleOffset(4.0, 4.0))
        }
        clip.children += leaf
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(root)
        val entered = PointerEvent.Move(IntOffset(6, 6))
        assertEquals(InputResult.Consumed, pipeline.dispatch(root, entered))
        assertEquals(listOf<Trace>(Trace.Hover(0, true), Trace.Input(0, entered, IntOffset(2, 2))), trace)
        trace.clear()
        assertEquals(InputResult.Ignored, pipeline.dispatch(root, PointerEvent.Move(IntOffset(8, 8))))
        assertEquals(listOf<Trace>(Trace.Hover(0, false)), trace)
        trace.clear()
        assertEquals(InputResult.Ignored, pipeline.dispatch(root, PointerEvent.Move(IntOffset(14, 14))))
        assertEquals(listOf<Trace>(Trace.Hover(0, false)), trace)
        pipeline.cancelCapture()
    }

    @Test
    fun resettingHoverAttemptsEveryObserverAndKeepsFailureSuppressionOrder() {
        val scene = broad(3, 1)
        val first = IllegalArgumentException("topmost hover exit")
        val second = IllegalStateException("last hover exit")
        (scene.participants[2].node as Participant).hoverFailure = first
        (scene.participants[0].node as Participant).hoverFailure = second
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(scene.root)
        assertSame(first, assertFailsWith<IllegalArgumentException> { pipeline.clearHover(scene.root) })
        assertEquals(listOf<Trace>(Trace.Hover(2, false), Trace.Hover(1, false), Trace.Hover(0, false)), scene.trace)
        assertEquals(listOf(second), first.suppressedExceptions)
        pipeline.cancelCapture()
    }

    private fun parity(
        factory: () -> Scene,
        events: List<PointerEvent>,
    ) {
        val actual = factory()
        val expected = factory()
        val pipeline = InputPipeline(FocusedInputPipeline())
        val oracle = OriginalPointer(expected.root)
        pipeline.layoutCommitted(actual.root)
        oracle.layoutCommitted()
        for (event in events) {
            actual.trace.clear()
            expected.trace.clear()
            assertEquals(oracle.dispatch(event), pipeline.dispatch(actual.root, event))
            assertEquals(expected.trace, actual.trace)
        }
        actual.trace.clear()
        expected.trace.clear()
        pipeline.cancelCapture()
        oracle.cancelCapture()
        pipeline.clearHover(actual.root)
        oracle.clearHover()
        assertEquals(expected.trace, actual.trace)
    }

    private fun broad(
        count: Int,
        step: Int,
        stop: Int = -1,
    ): Scene {
        val trace = ArrayList<Trace>()
        val root = retained(Passive())
        val participants = ArrayList<RetainedNode>()
        repeat(count) { id ->
            val node = if (step != 0 && id % step == 0) Participant(id, trace, consume = id == stop) else Passive()
            val child = retained(node, root)
            root.children += child
            if (node is Participant) participants += child
        }
        return Scene(root, participants, trace)
    }

    private fun deep(
        depth: Int,
        dense: Boolean,
        consume: Boolean = false,
    ): Scene {
        val trace = ArrayList<Trace>()
        val root = retained(Passive())
        val participants = ArrayList<RetainedNode>()
        var parent = root
        repeat(depth) { index ->
            val id = index + 1
            val node = if (dense || id == depth) Participant(id, trace, consume) else Passive()
            val child = retained(node, parent)
            parent.children += child
            if (node is Participant) participants += child
            parent = child
        }
        return Scene(root, participants, trace)
    }

    private fun transformed(clipped: Boolean): Scene {
        val trace = ArrayList<Trace>()
        val root = retained(if (clipped) Clip() else Passive())
        val parent = retained(Passive(), root).also {
            it.measuredSize = IntSize(1, 1)
            it.localToTree = TreeTransform(2.0, DoubleOffset(4.0, 6.0))
        }
        root.children += parent
        val child = retained(Participant(0, trace, consume = true), parent).also { it.localToTree = parent.localToTree }
        parent.children += child
        return Scene(root, listOf(child), trace)
    }

    private fun retained(
        node: Node,
        parent: RetainedNode? = null,
    ): RetainedNode =
        RetainedNode(Description(node), node, parent).also {
            it.parent = parent
            it.placed = true
            it.measuredSize = IntSize(10, 10)
        }

    private data class Scene(
        val root: RetainedNode,
        val participants: List<RetainedNode>,
        val trace: MutableList<Trace>,
    )

    private sealed interface Trace {
        data class Input(
            val id: Int,
            val event: PointerEvent,
            val local: IntOffset,
        ) : Trace
        data class Hover(
            val id: Int,
            val hovered: Boolean,
        ) : Trace
        data class Acquired(
            val id: Int,
            val button: PointerButton,
        ) : Trace
        data class Cancelled(
            val id: Int,
            val button: PointerButton,
        ) : Trace
    }

    private open class Passive : Node()

    private class Clip : Passive(), ClipChildrenNode

    private class Participant(
        private val id: Int,
        private val trace: MutableList<Trace>,
        private val consume: Boolean = false,
    ) : Node(), PointerCaptureNode, PointerHoverNode {
        var onInput: () -> Unit = {}
        var hoverFailure: Throwable? = null
        var cancelFailure: Throwable? = null

        override fun onPointerEvent(event: PointerEvent, localPosition: IntOffset): InputResult {
            trace += Trace.Input(id, event, localPosition)
            onInput()
            return if (consume) InputResult.Consumed else InputResult.Ignored
        }

        override fun onPointerHover(hovered: Boolean) {
            trace += Trace.Hover(id, hovered)
            hoverFailure?.let { throw it }
        }

        override fun onPointerCaptureAcquired(button: PointerButton) {
            trace += Trace.Acquired(id, button)
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            trace += Trace.Cancelled(id, button)
            cancelFailure?.let { throw it }
        }
    }

    private class InputOnly(
        private val trace: MutableList<Trace>,
    ) : Node(), PointerInputNode {
        override fun onPointerEvent(event: PointerEvent, localPosition: IntOffset): InputResult {
            trace += Trace.Input(0, event, localPosition)
            return if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
        }
    }

    private class HoverOnly(
        private val trace: MutableList<Trace>,
    ) : Node(), PointerHoverNode {
        override fun onPointerHover(hovered: Boolean) {
            trace += Trace.Hover(1, hovered)
        }
    }

    private class Description(
        val node: Node,
    ) : Element(ElementIdentity.Positional, descriptionType)

    /**
     * Original recursive candidate traversal, with independent coordinate arithmetic and no retained membership index.
     */
    private class OriginalPointer(
        private val root: RetainedNode,
    ) {
        private var captured: Pair<RetainedEntry, PointerButton>? = null

        fun dispatch(event: PointerEvent): InputResult {
            if (event is PointerEvent.Move || event is PointerEvent.Drag) hover(root.effectiveRoot, event.position, true)
            val current = captured
            val accepts =
                when (event) {
                    is PointerEvent.Move -> true
                    is PointerEvent.Drag -> event.button == current?.second
                    is PointerEvent.Release -> event.button == current?.second
                    is PointerEvent.Press, is PointerEvent.Scroll -> false
                }
            if (current != null && accepts) {
                if (event is PointerEvent.Release) captured = null
                deliver(current.first, event)
                return InputResult.Consumed
            }
            return visit(root.effectiveRoot, event, true)
        }

        fun layoutCommitted() {
            val current = captured ?: return
            if (placed(root.effectiveRoot, current.first).not()) cancelCapture()
        }

        fun cancelCapture() {
            val current = captured ?: return
            captured = null
            (current.first.node as PointerCaptureNode).onPointerCaptureCancelled(current.second)
        }

        fun clearHover() = reset(root.effectiveRoot)

        private fun reset(entry: RetainedEntry) {
            for (index in (0 until entry.effectiveChildCount).reversed()) reset(entry.effectiveChildAt(index))
            (entry.node as? PointerHoverNode)?.onPointerHover(false)
        }

        private fun hover(entry: RetainedEntry, position: IntOffset, allowed: Boolean) {
            val childrenAllowed = allowed && ((entry.node is ClipChildrenNode).not() || contains(entry, position))
            for (index in (0 until entry.effectiveChildCount).reversed()) {
                val child = entry.effectiveChildAt(index)
                if (child.placed) hover(child, position, childrenAllowed)
            }
            if (entry.placed) (entry.node as? PointerHoverNode)?.onPointerHover(allowed && contains(entry, position))
        }

        private fun visit(entry: RetainedEntry, event: PointerEvent, allowed: Boolean): InputResult {
            val childrenAllowed = allowed && ((entry.node is ClipChildrenNode).not() || contains(entry, event.position))
            if (childrenAllowed) {
                for (index in (0 until entry.effectiveChildCount).reversed()) {
                    val child = entry.effectiveChildAt(index)
                    if (child.placed) {
                        val result = visit(child, event, true)
                        if (result === InputResult.Consumed) return result
                    }
                }
            }
            if (allowed && contains(entry, event.position) && entry.node is PointerInputNode) {
                val result = deliver(entry, event)
                if (result === InputResult.Consumed && event is PointerEvent.Press && captured == null && entry.node is PointerCaptureNode) {
                    captured = entry to event.button
                    (entry.node as PointerCaptureNode).onPointerCaptureAcquired(event.button)
                }
                return result
            }
            return InputResult.Ignored
        }

        private fun deliver(entry: RetainedEntry, event: PointerEvent): InputResult {
            val transform = entry.localToTree
            val local = IntOffset(floor((event.position.x - transform.offset.x) / transform.scale).toInt(), floor((event.position.y - transform.offset.y) / transform.scale).toInt())
            val delivered = if (event is PointerEvent.Drag) PointerEvent.Drag(event.position, event.button, event.deltaX / transform.scale, event.deltaY / transform.scale) else event
            return (entry.node as PointerInputNode).onPointerEvent(delivered, local)
        }

        private fun contains(entry: RetainedEntry, position: IntOffset): Boolean {
            val transform = entry.localToTree
            val x = (position.x - transform.offset.x) / transform.scale
            val y = (position.y - transform.offset.y) / transform.scale
            return 0.0 <= x && x < entry.measuredSize.width && 0.0 <= y && y < entry.measuredSize.height
        }

        private fun placed(entry: RetainedEntry, owner: RetainedEntry): Boolean {
            if (entry.placed.not()) return false
            if (entry === owner) return true
            for (index in 0 until entry.effectiveChildCount) if (placed(entry.effectiveChildAt(index), owner)) return true
            return false
        }
    }

    private companion object {
        val inside = IntOffset(1, 1)
        val outside = IntOffset(50, 60)
        val descriptionType =
            ElementType(
                elementClass = Description::class,
                nodeClass = Node::class,
                validateLocal = {},
                createNode = Description::node,
                updateNode = { _, _, _ -> DirtyMask.None },
            )
    }
}
