package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.TraversalTestFixture.Event
import dev.s7a.strata.runtime.TraversalTestFixture.Kind
import dev.s7a.strata.runtime.TraversalTestFixture.Phase
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the public tree boundaries with live callback invalidation, ordering, placement and exact failure cleanup.
 */
internal class TraversalCallbackParityTest {
    @Test
    fun parentDynamicCallbackInstallsNestedChildrenBeforeTheirOwnAttachAndVisit() {
        val fixture = TraversalTestFixture()
        val tree = UiTree()
        tree.update(fixture.element(0, Kind.Dynamic))
        val parent = fixture.nodes.getValue(0) as TraversalTestFixture.DynamicProbe
        val nested = fixture.element(2, Kind.Dynamic)
        parent.descriptions = listOf(fixture.element(1), nested)
        fixture.callbacks[Event(Phase.Dynamic, 2)] = {
            (fixture.nodes.getValue(2) as TraversalTestFixture.DynamicProbe).descriptions = listOf(fixture.element(3, Kind.Participant))
        }
        fixture.events.clear()
        try {
            frame(tree)
            val construction = fixture.events.filter { it.phase == Phase.Attach || it.phase == Phase.Dynamic }
            assertEquals(
                listOf(Event(Phase.Dynamic, 0), Event(Phase.Attach, 1), Event(Phase.Attach, 2), Event(Phase.Dynamic, 2), Event(Phase.Attach, 3)),
                construction,
            )
            assertEquals(listOf(UiText.Literal("3")), tree.semantics().map { it.semantics.label })
            fixture.events.clear()
            tree.measure(Constraints.fixed(20, 20))
            assertEquals(listOf(Event(Phase.Dynamic, 0), Event(Phase.Dynamic, 2)), fixture.events.filter { it.phase == Phase.Attach || it.phase == Phase.Dynamic })
            parent.descriptions = listOf(nested)
            parent.dirty()
            fixture.events.clear()
            frame(tree)
            assertTrue(fixture.events.indexOf(Event(Phase.Detach, 1)) < fixture.events.indexOf(Event(Phase.Dynamic, 2)))
            assertEquals(1, fixture.events.count { it == Event(Phase.Dispose, 1) })
        } finally {
            tree.close()
        }
    }

    @Test
    fun modifierAncestryAndDeclaredOrderRemainVisibleToSemanticsAndTabTraversal() {
        val fixture = TraversalTestFixture()
        val tree = UiTree()
        val first = fixture.element(1, Kind.Participant, modifier = fixture.modifier(11))
        val second = fixture.element(2, Kind.Participant)
        val rootModifiers = fixture.modifier(10).then(fixture.modifier(20))
        tree.update(fixture.element(0, Kind.Participant, listOf(first, second), rootModifiers))
        try {
            frame(tree)
            assertEquals(listOf(10, 20, 0, 11, 1, 2).map { UiText.Literal(it.toString()) }, tree.semantics().map { it.semantics.label })
            fixture.events.clear()
            repeat(3) { assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0))) }
            assertEquals(listOf(20, 10, 0, 11, 1, 2), fixture.events.filter { it.phase == Phase.FocusGained }.map { it.id })
            tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0, KeyboardModifiers(shift = true)))
            assertEquals(
                listOf(11, 1),
                fixture.events
                    .filter { it.phase == Phase.FocusGained }
                    .takeLast(2)
                    .map { it.id },
            )
            tree.update(fixture.element(0, Kind.Participant, listOf(second, first), rootModifiers))
            frame(tree)
            assertEquals(listOf(10, 20, 0, 2, 11, 1).map { UiText.Literal(it.toString()) }, tree.semantics().map { it.semantics.label })
        } finally {
            tree.close()
        }
    }

    @Test
    fun focusAcceptanceInitialUniquenessAndUnplacedBranchesAreSampledAfterEveryLayout() {
        val fixture = TraversalTestFixture()
        val tree = UiTree()
        val first = fixture.element(1, Kind.Focus, initial = true)
        val second = fixture.element(2, Kind.Focus)
        tree.update(fixture.element(0, children = listOf(first, second)))
        try {
            frame(tree)
            assertEquals(listOf(Event(Phase.FocusGained, 1)), fixture.events.filter { it.phase == Phase.FocusGained })
            tree.update(fixture.element(0, children = listOf(fixture.element(1, Kind.Focus, accepting = false), second)))
            frame(tree)
            assertEquals(1, fixture.events.count { it == Event(Phase.FocusLost, 1) })
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(Event(Phase.FocusGained, 2), fixture.events.last())
            tree.update(fixture.element(0, children = listOf(first, second), unplacedChild = 0))
            frame(tree)
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(1, fixture.events.count { it == Event(Phase.FocusGained, 1) })
        } finally {
            tree.close()
        }
        val ambiguous = UiTree()
        ambiguous.update(fixture.element(0, children = listOf(first, fixture.element(2, Kind.Focus, initial = true))))
        ambiguous.measure(Constraints.fixed(20, 20))
        assertFailsWith<IllegalStateException> { ambiguous.layout() }
        assertEquals(TreeState.Poisoned, ambiguous.state)
        ambiguous.close()
    }

    @Test
    fun retainedOwnerWithoutTargetsPreservesReacquisitionAndItsOriginalTabPosition() {
        for (traverse in listOf(false, true)) {
            val fixture = TraversalTestFixture()
            val tree = UiTree()
            val first = fixture.element(1, Kind.Focus)
            val last = fixture.element(3, Kind.Focus)
            tree.update(fixture.element(0, children = listOf(first, fixture.element(2, modifier = fixture.modifier(12, initial = true)), last)))
            try {
                frame(tree)
                tree.update(fixture.element(0, children = listOf(first, fixture.element(2), last)))
                frame(tree)
                fixture.events.clear()
                if (traverse) {
                    tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0))
                    assertEquals(listOf(Event(Phase.FocusGained, 3)), fixture.events)
                } else {
                    tree.update(fixture.element(0, children = listOf(first, fixture.element(2, modifier = fixture.modifier(22)), last)))
                    frame(tree)
                    assertEquals(listOf(Event(Phase.FocusGained, 22)), fixture.events.filter { it.phase == Phase.FocusGained })
                }
            } finally {
                tree.close()
            }
        }
    }

    @Test
    fun semanticsInvalidationFromAnEarlierCallbackReachesLaterParticipantsAndRemainsPendingForEarlierOnes() {
        val fixture = TraversalTestFixture()
        val tree = UiTree()
        tree.update(fixture.element(0, children = listOf(fixture.element(1, Kind.Semantics), fixture.element(2, Kind.Semantics))))
        try {
            frame(tree)
            fixture.events.clear()
            fixture.nodes.getValue(1).dirty(DirtyPhase.Semantics)
            fixture.callbacks[Event(Phase.Semantics, 1)] = { fixture.nodes.getValue(2).dirty(DirtyPhase.Semantics) }
            fixture.callbacks[Event(Phase.Semantics, 2)] = { fixture.nodes.getValue(1).dirty(DirtyPhase.Semantics) }
            assertEquals(2, tree.semantics().size)
            assertEquals(listOf(Event(Phase.Semantics, 1), Event(Phase.Semantics, 2)), fixture.events)
            fixture.callbacks.clear()
            fixture.events.clear()
            tree.semantics()
            assertEquals(listOf(Event(Phase.Semantics, 1)), fixture.events)
        } finally {
            tree.close()
        }
    }

    @Test
    fun callbackFailuresPreserveThePrimaryAndAttemptAllIndependentTerminalCleanup() {
        for (phase in listOf(Phase.Attach, Phase.Dynamic, Phase.Semantics, Phase.FocusGained)) {
            val fixture = TraversalTestFixture()
            val primary = IllegalArgumentException("callback failure")
            val cleanup = IllegalStateException("cleanup failure")
            val tree = UiTree()
            val kind = if (phase == Phase.Dynamic) Kind.Dynamic else Kind.Participant
            val action: () -> Unit = {
                tree.update(fixture.element(0, children = listOf(fixture.element(1, kind), fixture.element(2, Kind.Participant))))
                frame(tree)
                tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0))
            }
            fixture.callbacks[Event(phase, 1)] = { throw primary }
            fixture.callbacks[Event(Phase.Dispose, 2)] = { throw cleanup }
            assertSame(primary, assertFailsWith<IllegalArgumentException>(block = action))
            assertSame(cleanup, primary.suppressedExceptions.single())
            assertEquals(TreeState.Poisoned, tree.state)
            assertEquals(listOf(2, 1, 0), fixture.events.filter { it.phase == Phase.Dispose }.map { it.id })
            fixture.nodes.values.forEach { node -> assertFailsWith<IllegalStateException> { node.dirty() } }
            tree.close()
            assertEquals(3, fixture.events.count { it.phase == Phase.Dispose })
        }
    }

    private fun frame(tree: UiTree) {
        tree.measure(Constraints.fixed(20, 20))
        tree.layout()
        tree.paint()
        tree.semantics()
    }
}
