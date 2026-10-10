@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Independent ordered-output and scope-lifetime checks for every local collection cardinality on JVM/JavaScript.
 */
internal class SmallSemanticsCollectionTest {
    @Test
    fun mixedCallbacksPreserveValuesBoundsVirtualAncestryUnplacedEntriesAndInput() {
        val fixture = SemanticsCollectionFixture()
        val passive = fixture.node(1, participates = false)
        val empty = fixture.node(2)
        val one = fixture.node(3, listOf(payload("one")))
        val many = fixture.node(4, List(8) { payload("many-$it") })
        val hidden = fixture.node(5, listOf(payload("hidden")))
        val root = fixture.node(0, participates = false)
        root.placedIndices = setOf(0, 1, 2, 3)
        val children = listOf(passive, empty, one, many, hidden).map { node ->
            fixture.element(node, modifier = if (node === one) Modifier.Empty.semantics(payload("modifier")) else Modifier.Empty)
        }
        val session = createRuntimeUiSession { fixture.element(root, children, Modifier.Empty.semantics(payload("outer"))) }
        try {
            session.attach()
            val frame = session.frame(Constraints.fixed(16, 4))
            val expected =
                listOf(
                    SemanticsEntry(IntRect(0, 0, 16, 4), payload("outer")),
                    SemanticsEntry(IntRect(3, 0, 4, 1), payload("modifier")),
                    SemanticsEntry(IntRect(3, 0, 4, 1), payload("one")),
                ) + List(8) { SemanticsEntry(IntRect(4, 0, 5, 1), payload("many-$it")) }
            assertEquals(expected, frame.semantics)
            assertEquals(listOf(0, 1, 1, 1, 0), listOf(passive, empty, one, many, hidden).map { it.callbacks })
            assertNull(passive.scope)
            assertNull(hidden.scope)
            for (node in listOf(empty, one, many)) {
                assertFailsWith<IllegalStateException> { checkNotNull(node.scope).emit(payload("late")) }
            }
            assertSame(frame, session.frame(Constraints.fixed(16, 4)))
            assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset(1, 0))))
            assertEquals(1, passive.inputs)
            assertEquals(expected, frame.semantics)
        } finally {
            session.close()
        }
        assertEquals(listOf(5, 4, 3, 2, 1, 0), fixture.disposed)
    }

    @Test
    fun emptyAndSmallPayloadsPreserveSelfInvalidationAndPaintOnlyReuse() {
        for (count in listOf(0, 1, 8)) {
            val fixture = SemanticsCollectionFixture()
            val values = List(count) { payload("$count-$it") }
            val node = fixture.node(0, values)
            var invalidated = false
            node.emit = { scope ->
                values.forEach(scope::emit)
                if (invalidated.not()) {
                    invalidated = true
                    node.invalidatePhase(DirtyPhase.Semantics)
                }
            }
            val tree = UiTree()
            try {
                tree.update(fixture.element(node))
                tree.measure(Constraints.fixed(2, 1))
                tree.layout()
                val first = tree.semantics()
                val expected = values.map { SemanticsEntry(IntRect(0, 0, 2, 1), it) }
                assertEquals(expected, first)
                assertEquals(1, node.callbacks)
                assertEquals(expected, tree.semantics())
                assertEquals(2, node.callbacks)
                node.invalidatePhase(DirtyPhase.Paint)
                tree.paint()
                assertEquals(expected, tree.semantics())
                assertEquals(2, node.callbacks)
                tree.measure(Constraints.fixed(3, 1))
                tree.layout()
                assertEquals(values.map { SemanticsEntry(IntRect(0, 0, 3, 1), it) }, tree.semantics())
                assertEquals(3, node.callbacks)
                assertEquals(expected, first)
            } finally {
                tree.close()
            }
        }
    }

    @Test
    fun priorFramesStayDetachedAfterPayloadChangesReorderRemovalDetachAndClose() {
        val fixture = SemanticsCollectionFixture()
        val root = fixture.node(0, participates = false)
        val first = fixture.node(1, listOf(payload("first")))
        val second = fixture.node(2, listOf(payload("second"), payload("third")))
        val declaration = mutableStateOf(fixture.element(root, listOf(fixture.element(first), fixture.element(second))))
        val session = createRuntimeUiSession { declaration.value }
        session.attach()
        val constraints = Constraints.fixed(8, 1)
        val initial = session.frame(constraints)
        val expected = initial.semantics.toList()
        first.values = listOf(payload("changed"))
        first.invalidatePhase(DirtyPhase.Semantics)
        assertEquals(payload("changed"), session.frame(constraints).semantics.first().semantics)
        assertEquals(expected, initial.semantics)
        declaration.value = fixture.element(root, listOf(fixture.element(second), fixture.element(first)))
        val reordered = session.frame(constraints)
        assertEquals(listOf(payload("second"), payload("third"), payload("changed")), reordered.semantics.map { it.semantics })
        assertEquals(expected, initial.semantics)
        declaration.value = fixture.element(root, listOf(fixture.element(first)))
        assertEquals(listOf(payload("changed")), session.frame(constraints).semantics.map { it.semantics })
        assertEquals(listOf(2), fixture.disposed)
        session.detach()
        session.attach()
        assertEquals(listOf(payload("changed")), session.frame(constraints).semantics.map { it.semantics })
        session.close()
        assertEquals(listOf(2, 1, 0), fixture.disposed)
        assertEquals(expected, initial.semantics)
        assertEquals(listOf(payload("second"), payload("third"), payload("changed")), reordered.semantics.map { it.semantics })
        assertFailsWith<IllegalStateException> { checkNotNull(first.scope).emit(payload("late")) }
    }

    @Test
    fun callbackFailuresBeforeAndAfterBufferGrowthCloseScopesAndPreservePrimaryAndDisposalOrder() {
        for (emissions in 0..2) {
            val fixture = SemanticsCollectionFixture()
            val root = fixture.node(0, participates = false)
            val node = fixture.node(1, listOf(payload("committed")))
            val sibling = fixture.node(2)
            val session = createRuntimeUiSession { fixture.element(root, listOf(fixture.element(node), fixture.element(sibling))) }
            session.attach()
            val constraints = Constraints.fixed(8, 1)
            val initial = session.frame(constraints)
            val expected = initial.semantics.toList()
            val failure = IllegalStateException("Callback failed")
            val cleanupFailure = IllegalArgumentException("Cleanup failed")
            node.disposeFailure = cleanupFailure
            node.emit = { scope ->
                repeat(emissions) { scope.emit(payload("new-$it")) }
                throw failure
            }
            node.invalidatePhase(DirtyPhase.Semantics)
            assertSame(failure, assertFailsWith<IllegalStateException> { session.frame(constraints) })
            assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
            assertEquals(listOf(2, 1, 0), fixture.disposed)
            assertEquals(expected, initial.semantics)
            assertFailsWith<IllegalStateException> { checkNotNull(node.scope).emit(payload("late")) }
            session.close()
            assertEquals(listOf(2, 1, 0), fixture.disposed)
        }
    }

    @Test
    fun refreshedNonparticipantStillChecksTheExecutionOwner() {
        val owner = RuntimeExecutionOwner()
        val other = RuntimeExecutionOwner()
        val (pipeline, entry) = owner.run {
            val fixture = SemanticsCollectionFixture()
            val node = fixture.node(0, participates = false)
            val entry = RetainedNode(fixture.element(node), node, null)
            SemanticsPipeline(OwnerGuard()) to entry
        }
        other.run { assertFailsWith<IllegalStateException> { pipeline.semantics(entry) } }
        assertNull(entry.localSemantics)
        owner.run { assertEquals(emptyList(), pipeline.semantics(entry)) }
    }

    private fun payload(label: String): Semantics = Semantics(label = UiText.Literal(label))
}
