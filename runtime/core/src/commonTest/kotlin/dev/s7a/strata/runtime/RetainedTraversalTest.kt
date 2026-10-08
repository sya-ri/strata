package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.TraversalTestFixture.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Proves exact current-child membership, reuse, propagation, bounded retention and terminal release on both targets.
 */
internal class RetainedTraversalTest {
    @Test
    fun broadTreesRetainOnlyRelevantChildrenAndGeometryDoesNotReplaceTheirLists() {
        for (participants in listOf(0, 10, 1024)) {
            val fixture = TraversalTestFixture()
            val children = List(1024) { id -> fixture.element(id + 1, if (id < participants) Kind.Participant else Kind.Plain) }
            val (root, lifecycle) = fixture.reconcile(fixture.element(0, children = children))
            try {
                assertEquals(participants, root.focusChildren.size)
                assertEquals(participants, root.semanticsChildren.size)
                assertTrue(root.refreshChildren.isEmpty())
                assertTrue(root.attachmentChildren.isEmpty())
                assertFalse(root.hasPendingAttachments)
                assertEquals(participants != 0, root.hasFocusTargets)
                assertEquals(participants != 0, root.hasSemantics)
                val focus = root.focusChildren
                val semantics = root.semanticsChildren
                fixture.nodes.getValue(1024).dirty(DirtyPhase.Measure)
                root.refreshTraversalSummary()
                assertSame(focus, root.focusChildren)
                assertSame(semantics, root.semanticsChildren)
                assertBound(root)
            } finally {
                lifecycle.cleanup(root)
            }
        }
    }

    @Test
    fun deepTreesKeepOnlyTheParticipantPathAndNeverStoreFlattenedDescendantLists() {
        val fixture = TraversalTestFixture()
        var description: Element = fixture.element(1000, Kind.Participant)
        repeat(128) { depth ->
            description = fixture.element(depth, children = listOf(description, fixture.element(depth + 2000)))
        }
        val (root, lifecycle) = fixture.reconcile(description)
        try {
            var current = root
            repeat(128) {
                assertEquals(1, current.focusChildren.size)
                assertEquals(1, current.semanticsChildren.size)
                assertTrue(current.refreshChildren.isEmpty())
                assertTrue(current.attachmentChildren.isEmpty())
                current = current.focusChildren.single()
            }
            assertTrue(current.ownsFocusTargets)
            assertTrue(current.focusChildren.isEmpty())
            assertBound(root)
        } finally {
            lifecycle.cleanup(root)
        }
    }

    @Test
    fun reorderRemovalReplacementAndModifierChangesUpdateAllAncestorMembership() {
        val fixture = TraversalTestFixture()
        val tracker = DirtyTracker()
        val lifecycle = LifecycleManager(NodeOwnershipRegistry(), OwnerGuard(), tracker) {}
        val reconciler = Reconciler(lifecycle, tracker)
        val first = fixture.element(1, Kind.Participant)
        val second = fixture.element(2, Kind.Dynamic)
        val root = reconciler.reconcileRoot(null, fixture.element(0, children = listOf(first, second)))
        reconciler.markInstalled(root)
        lifecycle.attachPending(root)
        val retired = root.children[0]
        try {
            reconciler.reconcileRoot(root, fixture.element(0, children = listOf(second, first)))
            assertSame(retired, root.children[1])
            assertSame(root.children[0], root.refreshChildren.single())
            assertSame(retired, root.focusChildren.single())
            val replacement = fixture.element(1, modifier = fixture.modifier(11))
            reconciler.reconcileRoot(root, fixture.element(0, children = listOf(replacement)))
            assertReleased(retired)
            assertTrue(root.hasPendingAttachments)
            assertSame(root.children.single(), root.attachmentChildren.single())
            lifecycle.attachPending(root)
            assertTrue(root.refreshChildren.isEmpty())
            assertSame(root.children.single(), root.focusChildren.single())
            assertSame(root.children.single(), root.semanticsChildren.single())
            repeat(100) { generation ->
                val modifier = fixture.modifier(100 + generation, participant = generation % 2 == 0)
                reconciler.reconcileRoot(root, fixture.element(0, children = listOf(fixture.element(1, modifier = modifier))))
                lifecycle.attachPending(root)
                assertEquals(if (generation % 2 == 0) 1 else 0, root.focusChildren.size)
                assertEquals(root.focusChildren.size, root.semanticsChildren.size)
                assertTrue(root.attachmentChildren.isEmpty())
                assertBound(root)
            }
        } finally {
            lifecycle.cleanup(root)
        }
        assertReleased(root)
    }

    @Test
    fun terminalCleanupClearsEverySummaryBeforeAnyFailingCallbackAndRejectsAllInvalidation() {
        val fixture = TraversalTestFixture()
        val primary = IllegalArgumentException("detach")
        val later = IllegalStateException("dispose")
        val tracker = DirtyTracker()
        var captured: RetainedNode? = null
        val lifecycle = LifecycleManager(NodeOwnershipRegistry(), OwnerGuard(), tracker) {
            val root = checkNotNull(captured)
            assertReleased(root)
            root.children.forEach(::assertReleased)
            fixture.nodes.values.forEach { node -> assertFailsWith<IllegalStateException> { node.dirty() } }
        }
        val reconciler = Reconciler(lifecycle, tracker)
        val root = reconciler.reconcileRoot(null, fixture.element(0, children = listOf(fixture.element(1, Kind.Participant), fixture.element(2, Kind.Dynamic))))
        captured = root
        reconciler.markInstalled(root)
        lifecycle.attachPending(root)
        fixture.callbacks[TraversalTestFixture.Event(TraversalTestFixture.Phase.Detach, 2)] = { throw primary }
        fixture.callbacks[TraversalTestFixture.Event(TraversalTestFixture.Phase.Dispose, 2)] = { throw later }
        assertSame(primary, lifecycle.cleanup(root))
        assertSame(later, primary.suppressedExceptions.single())
        assertEquals(listOf(2, 1, 0), fixture.events.filter { it.phase == TraversalTestFixture.Phase.Dispose }.map { it.id })
    }

    @Test
    fun independentOwnersNeverBorrowAnotherTreesParticipantsOrPendingAttachments() {
        val first = TraversalTestFixture()
        val second = TraversalTestFixture()
        val (left, leftLifecycle) = first.reconcile(first.element(0, children = listOf(first.element(1, Kind.Participant))))
        val (right, rightLifecycle) = second.reconcile(second.element(0, children = listOf(second.element(1, Kind.Dynamic))))
        try {
            assertSame(left.children.single(), left.focusChildren.single())
            assertTrue(right.focusChildren.isEmpty())
            assertSame(right.children.single(), right.refreshChildren.single())
            assertTrue(left.refreshChildren.isEmpty())
            leftLifecycle.cleanup(left)
            assertReleased(left)
            assertSame(right.children.single(), right.refreshChildren.single())
        } finally {
            leftLifecycle.cleanup(left)
            rightLifecycle.cleanup(right)
        }
    }

    private fun assertBound(root: RetainedNode) {
        var references = 0
        var edges = 0
        fun visit(current: RetainedNode) {
            val lists = listOf(current.refreshChildren, current.focusChildren, current.semanticsChildren, current.attachmentChildren)
            for (members in lists) {
                references += members.size
                var previous = -1
                for (child in members) {
                    val index = current.children.indexOfFirst { it === child }
                    assertTrue(previous < index)
                    previous = index
                }
            }
            edges += current.children.size
            current.children.forEach(::visit)
        }
        visit(root)
        assertTrue(references <= edges * 4)
    }

    private fun assertReleased(root: RetainedNode) {
        assertTrue(root.refreshChildren.isEmpty())
        assertTrue(root.focusChildren.isEmpty())
        assertTrue(root.semanticsChildren.isEmpty())
        assertTrue(root.attachmentChildren.isEmpty())
        assertFalse(root.hasContentParticipants)
        assertFalse(root.hasFocusTargets)
        assertFalse(root.hasSemantics)
        assertFalse(root.hasPendingAttachments)
    }
}
