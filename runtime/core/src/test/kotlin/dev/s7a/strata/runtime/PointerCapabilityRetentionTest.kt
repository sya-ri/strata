@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Inspects the private owner-confined index to prove current-state bounds and release independently of event output.
 * Reflection stays in JVM tests and adds no production diagnostic or ABI member.
 */
internal class PointerCapabilityRetentionTest {
    @Test
    fun sparseIndexRetainsOnlyCapabilitiesAndRequiredClipsAndReusesTheirIdentity() {
        val root = retained(Passive())
        repeat(5_000) { root.children += retained(Passive(), root) }
        val emptyClip = retained(Clip(), root)
        root.children += emptyClip
        emptyClip.children += retained(Passive(), emptyClip)
        val clip = retained(Clip(), root)
        root.children += clip
        val inner = retained(Clip(), clip)
        clip.children += inner
        val passive = retained(Passive(), inner)
        inner.children += passive
        val first = retained(Participant(), passive)
        passive.children += first
        val second = retained(Participant(), root)
        root.children += second
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(root)
        pipeline.dispatch(root, move)
        val entries = index(pipeline)
        val owners = owners(entries)
        assertEquals(listOf(second, clip, inner, first), owners)
        assertEquals(4, owners.size)
        assertTrue(owners.none { it === root || it === emptyClip || it === passive })
        repeat(100) {
            pipeline.dispatch(root, move)
            assertSame(entries, index(pipeline))
        }
        pipeline.cancelCapture()
        assertReleased(pipeline)
    }

    @Test
    fun replacementAndRepeatedLayoutRetainOnlyTheCurrentTreeWithoutHistory() {
        val pipeline = InputPipeline(FocusedInputPipeline())
        var previous: List<*> = emptyList<Any>()
        repeat(100) { generation ->
            val root = retained(Passive())
            repeat(generation % 7) { root.children += retained(Participant(), root) }
            pipeline.layoutCommitted(root)
            assertReleased(pipeline)
            pipeline.dispatch(root, move)
            val current = index(pipeline)
            val currentOwners = owners(current)
            assertEquals(root.children.reversed(), currentOwners)
            assertTrue(previous.none { old -> current.any { entry -> entry === old } })
            assertSame(root, field(pipeline, "indexedRoot"))
            previous = current
        }
        pipeline.cancelCapture()
        assertReleased(pipeline)
    }

    @Test
    fun paintDirtinessKeepsMembershipWhileGeometryCommitDropsTheOldIndex() {
        val root = retained(Passive())
        val leaf = retained(Participant(), root)
        root.children += leaf
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(root)
        pipeline.dispatch(root, move)
        val before = index(pipeline)
        leaf.dirty += DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)
        pipeline.dispatch(root, move)
        assertSame(before, index(pipeline))
        pipeline.layoutCommitted(root)
        assertReleased(pipeline)
        pipeline.dispatch(root, move)
        assertNotSame(before, index(pipeline))
        assertEquals(listOf(leaf), owners(index(pipeline)))
    }

    @Test
    fun everyEntryCleanupEvictsMembershipEvenWhenItDoesNotOwnCapture() {
        val root = retained(Passive())
        val leaf = retained(Participant(), root)
        root.children += leaf
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(root)
        pipeline.dispatch(root, move)
        pipeline.entryWillCleanup(root)
        assertReleased(pipeline)
        pipeline.dispatch(root, move)
        val entries = index(pipeline)
        root.children.clear()
        pipeline.entryWillCleanup(leaf)
        assertReleased(pipeline)
        pipeline.layoutCommitted(root)
        pipeline.dispatch(root, move)
        assertTrue(index(pipeline).isEmpty())
        assertEquals(listOf(leaf), owners(entries))
    }

    @Test
    fun cancellationFailureSeesReferencesClearedBeforeTheCallback() {
        val root = retained(Passive())
        val participant = Participant()
        val leaf = retained(participant, root)
        root.children += leaf
        val pipeline = InputPipeline(FocusedInputPipeline())
        val failure = IllegalStateException("capture cancellation")
        participant.cancel = {
            assertReleased(pipeline)
            assertNull(field(pipeline, "capture"))
            throw failure
        }
        pipeline.layoutCommitted(root)
        pipeline.dispatch(root, press)
        pipeline.dispatch(root, move)
        leaf.placed = false
        assertSame(failure, assertThrows(IllegalStateException::class.java) { pipeline.layoutCommitted(root) })
        assertReleased(pipeline)
        pipeline.cancelCapture()
        assertEquals(1, participant.cancellations)
    }

    @Test
    fun independentOwnersKeepDistinctCurrentIndexesAndReleaseWithoutAffectingTheOther() {
        val firstRoot = retained(Participant())
        val secondRoot = retained(Participant())
        val first = InputPipeline(FocusedInputPipeline())
        val second = InputPipeline(FocusedInputPipeline())
        first.layoutCommitted(firstRoot)
        second.layoutCommitted(secondRoot)
        first.dispatch(firstRoot, move)
        second.dispatch(secondRoot, move)
        val firstEntries = index(first)
        val secondEntries = index(second)
        assertNotSame(firstEntries, secondEntries)
        assertEquals(listOf(firstRoot), owners(firstEntries))
        assertEquals(listOf(secondRoot), owners(secondEntries))
        first.cancelCapture()
        assertReleased(first)
        assertSame(secondEntries, index(second))
        second.cancelCapture()
        assertReleased(second)
    }

    @Test
    fun actualTreeCloseAndHoverFailureReleaseTheIndexWhileTheTreeRemainsReachable() {
        for (failed in listOf(false, true)) {
            val primary = IllegalArgumentException("pointer hover")
            var rejectHover = false
            val modifier =
                Modifier.Empty
                    .size(10, 10)
                    .onMove { InputResult.Ignored }
                    .onHover { if (rejectHover) throw primary }
            val tree = tree(modifier)
            val pipeline = field(checkNotNull(field(tree, "pipeline")), "inputPipeline") as InputPipeline
            tree.dispatchPointer(move)
            assertTrue(index(pipeline).isNotEmpty())
            if (failed) {
                rejectHover = true
                assertSame(primary, assertThrows(IllegalArgumentException::class.java) { tree.dispatchPointer(PointerEvent.Move(IntOffset(50, 60))) })
                assertEquals(TreeState.Poisoned, tree.state)
            }
            tree.close()
            assertReleased(pipeline)
            assertNull(field(tree, "root"))
            assertEquals(TreeState.Closed, tree.state)
        }
    }

    @Test
    fun actualTreeCloseWithFailingCancellationStillDropsAllPointerReferencesOnce() {
        val primary = IllegalStateException("pointer cancellation")
        var cancellations = 0
        lateinit var pipeline: InputPipeline
        val modifier =
            Modifier.Empty.size(10, 10).onCapturedPointerEvent({
                cancellations += 1
                assertReleased(pipeline)
                assertNull(field(pipeline, "capture"))
                throw primary
            }) { event, _ -> if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored }
        val tree = tree(modifier)
        pipeline = field(checkNotNull(field(tree, "pipeline")), "inputPipeline") as InputPipeline
        tree.dispatchPointer(press)
        assertTrue(index(pipeline).isNotEmpty())
        assertSame(primary, assertThrows(IllegalStateException::class.java) { tree.close() })
        assertReleased(pipeline)
        assertNull(field(tree, "root"))
        tree.close()
        assertEquals(1, cancellations)
    }

    @Test
    fun denseTreesHaveOneDescriptorPerParticipantAndNoDuplicateRetainedEdges() {
        for (count in listOf(0, 128, 10_000)) {
            val root = retained(Passive())
            repeat(count) { root.children += retained(Participant(), root) }
            val pipeline = InputPipeline(FocusedInputPipeline())
            pipeline.layoutCommitted(root)
            pipeline.dispatch(root, move)
            val current = owners(index(pipeline))
            assertEquals(count, current.size)
            assertEquals(root.children.reversed(), current)
            assertEquals(count, current.toSet().size)
            pipeline.cancelCapture()
            assertReleased(pipeline)
        }
    }

    @Test
    fun revisionRolloverStillEvictsThePreviousGenerationBeforeReuse() {
        val root = retained(Participant())
        val pipeline = InputPipeline(FocusedInputPipeline())
        pipeline.layoutCommitted(root)
        pipeline.dispatch(root, move)
        val before = index(pipeline)
        pipeline.javaClass.getDeclaredField("layoutRevision").also { it.isAccessible = true }.setLong(pipeline, Long.MAX_VALUE)
        pipeline.layoutCommitted(root)
        assertReleased(pipeline)
        pipeline.dispatch(root, move)
        assertNotSame(before, index(pipeline))
        assertEquals(listOf(root), owners(index(pipeline)))
        pipeline.cancelCapture()
        assertReleased(pipeline)
    }

    private fun tree(modifier: Modifier): UiTree =
        UiTree().also {
            it.update(evaluateComponentTree { Spacer(modifier = modifier) })
            it.measure(Constraints.fixed(10, 10))
            it.layout()
        }

    private fun assertReleased(pipeline: InputPipeline) {
        assertNull(field(pipeline, "indexedRoot"))
        assertTrue(index(pipeline).isEmpty())
    }

    private fun index(pipeline: InputPipeline): List<*> = field(pipeline, "pointerEntries") as List<*>

    private fun owners(entries: List<*>): List<RetainedEntry> =
        buildList {
            for (entry in entries) {
                add(field(checkNotNull(entry), "owner") as RetainedEntry)
                val children = field(checkNotNull(entry), "children") as List<*>
                addAll(owners(children.filterNotNull()))
            }
        }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)

    private fun retained(node: Node, parent: RetainedNode? = null): RetainedNode =
        RetainedNode(Description(node), node, parent).also {
            it.parent = parent
            it.placed = true
            it.measuredSize = IntSize(10, 10)
        }

    private open class Passive : Node()

    private class Clip : Passive(), ClipChildrenNode

    private class Participant : Node(), PointerCaptureNode, PointerHoverNode {
        var cancellations = 0
        var cancel: () -> Unit = {}

        override fun onPointerEvent(event: PointerEvent, localPosition: IntOffset): InputResult =
            if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored

        override fun onPointerHover(hovered: Boolean) = Unit

        override fun onPointerCaptureCancelled(button: PointerButton) {
            cancellations += 1
            cancel()
        }
    }

    private class Description(
        val node: Node,
    ) : Element(ElementIdentity.Positional, descriptionType)

    private companion object {
        val move = PointerEvent.Move(IntOffset(1, 1))
        val press = PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)
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
