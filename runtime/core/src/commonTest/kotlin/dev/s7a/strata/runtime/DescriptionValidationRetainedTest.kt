@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Public retained outcomes independently check mutation cutoff, retries, observed failure, lifecycle and clean work.
 */
internal class DescriptionValidationRetainedTest {
    @Test
    fun c25ValidationReentry() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0))
            val invalid =
                probe.element(
                    1,
                    validate = {
                        assertFailsWith<IllegalStateException> { tree.close() }
                        tree.update(probe.element(2))
                    },
                )
            assertFailsWith<IllegalStateException> { tree.update(invalid) }
            assertEquals(TreeState.Active, tree.state)
            tree.update(probe.element(3))
            assertEquals(1, probe.nodes.size)
        }
    }

    @Test
    fun c26ValidationStateWrite() {
        val state = mutableStateOf(0)
        val probe = DescriptionValidationProbe()
        val failure =
            assertFailsWith<IllegalStateException> {
                createRuntimeUiSession {
                    val value = state.value
                    probe.element(value, validate = { state.value += 1 })
                }.use { session ->
                    session.attach()
                    session.frame(Constraints.fixed(2, 2))
                }
            }
        assertTrue(checkNotNull(failure.message).isNotEmpty())
        assertEquals(0, state.value)
        assertTrue(probe.nodes.isEmpty())
    }

    @Test
    fun c27RetainedRetry() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, listOf(probe.element(1, key = probe.key(1)))))
            assertEquals(IntSize(2, 2), tree.measure(Constraints.fixed(2, 2)))
            tree.layout()
            val paint = tree.paint()
            val semantics = tree.semantics()
            val nodes = probe.nodes.toList()
            assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)))
            val focus = nodes.map { it.focused }
            assertEquals(listOf(false, true), focus)
            assertEquals(InputResult.Consumed, tree.dispatchTextInput(TextInputEvent.Character(65)))
            val editing = nodes.map { it.editing }
            val history = probe.trace.toList()
            assertFailsWith<IllegalArgumentException> {
                tree.update(probe.element(2, listOf(probe.element(3, key = probe.key(3, value = 0)), probe.element(4, key = probe.key(4, value = 0)))))
            }
            assertEquals(TreeState.Active, tree.state)
            assertEquals(nodes, probe.nodes)
            assertEquals(focus, nodes.map { it.focused })
            assertEquals(editing, nodes.map { it.editing })
            assertEquals(history.filterIsInstance<DescriptionValidationProbe.Event.Ownership>(), probe.trace.filterIsInstance<DescriptionValidationProbe.Event.Ownership>())
            assertSame(paint, tree.paint())
            assertEquals(semantics, tree.semantics())
            assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
            assertEquals(InputResult.Consumed, tree.dispatchTextInput(TextInputEvent.Character(66)))
            assertEquals(editing.sum() + 66, nodes.sumOf { it.editing })
        }
    }

    @Test
    fun c28RetryValidRoot() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            val key = probe.key(1)
            tree.update(probe.element(0, listOf(probe.element(1, key = key))))
            val node = probe.nodes.last()
            node.editing = 12
            assertFailsWith<IllegalArgumentException> { tree.update(probe.element(2, listOf(probe.element(3, key = key), probe.element(4, key = key)))) }
            tree.update(probe.element(5, listOf(probe.element(6, key = key))))
            assertSame(node, probe.nodes.last())
            assertEquals(6, node.id)
            assertEquals(12, node.editing)
        }
    }

    @Test
    fun c29DynamicDuplicate() {
        val probe = DescriptionValidationProbe()
        val source = DescriptionValidationSource(false)
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Observe(source) { duplicate ->
                        element(probe.element(1, key = probe.key(1)))
                        if (duplicate) element(probe.element(2, key = probe.key(2, value = 1)))
                    }
                }
            }
        session.attach()
        session.frame(Constraints.fixed(2, 2))
        val nodes = probe.nodes.toList()
        source.publish(true)
        assertFailsWith<IllegalArgumentException> { session.frame(Constraints.fixed(2, 2)) }
        assertEquals(nodes, probe.nodes)
        assertEquals(1, source.releases)
        assertFailsWith<IllegalStateException> { session.frame(Constraints.fixed(2, 2)) }
        val cleaned = probe.trace.filterIsInstance<DescriptionValidationProbe.Event.Ownership>()
        session.close()
        assertEquals(cleaned, probe.trace.filterIsInstance<DescriptionValidationProbe.Event.Ownership>())
    }

    @Test
    fun c30DynamicLocalFailure() {
        val primary = IllegalStateException("dynamic validation")
        val later = IllegalStateException("dispose")
        val source = DescriptionValidationSource(false)
        val probe = DescriptionValidationProbe()
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Observe(source) { fail ->
                        element(probe.element(1, validate = { if (fail) throw primary }, dispose = { throw later }))
                    }
                }
            }
        session.attach()
        session.frame(Constraints.fixed(2, 2))
        source.publish(true)
        assertSame(primary, assertFailsWith<IllegalStateException> { session.frame(Constraints.fixed(2, 2)) })
        assertEquals(listOf(later), primary.suppressedExceptions)
        assertEquals(1, source.releases)
        session.close()
    }

    @Test
    fun c33KeyedReorder() {
        val probe = DescriptionValidationProbe()
        val keys = List(3) { probe.key(it) }
        UiTree().use { tree ->
            tree.update(probe.element(9, List(3) { probe.element(it, key = keys[it]) }))
            val children = probe.nodes.drop(1)
            children.forEachIndexed { index, node -> node.editing = index + 10 }
            probe.trace.clear()
            tree.update(probe.element(9, listOf(2, 0, 1).map { probe.element(it, key = keys[it]) }))
            assertEquals(4, probe.nodes.size)
            assertEquals(
                listOf(2, 0, 1),
                probe.trace
                    .filterIsInstance<DescriptionValidationProbe.Event.Ownership>()
                    .filter { it.phase == DescriptionValidationProbe.Phase.Update && it.id != 9 }
                    .map { it.id },
            )
            assertEquals(listOf(10, 11, 12), children.map { it.editing })
        }
    }

    @Test
    fun c34KeyReplacement() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, listOf(probe.element(1, key = probe.key(1)))))
            val old = probe.nodes.last()
            old.editing = 17
            tree.update(probe.element(0, listOf(probe.element(2, key = probe.key(2)))))
            assertNotSame(old, probe.nodes.last())
            assertEquals(0, probe.nodes.last().editing)
            assertEquals(1, probe.trace.count { it == DescriptionValidationProbe.Event.Ownership(DescriptionValidationProbe.Phase.Dispose, 1) })
        }
    }

    @Test
    fun c35ModifierReplacement() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, modifier = Modifier.Empty.then(probe.modifier(1))))
            val component = probe.nodes.single()
            tree.update(probe.element(0))
            tree.update(probe.element(0, modifier = Modifier.Empty.then(probe.modifier(2))))
            assertSame(component, probe.nodes.single())
            assertEquals(1, probe.trace.count { it == DescriptionValidationProbe.Event.Ownership(DescriptionValidationProbe.Phase.ModifierDispose, 1) })
            assertEquals(1, probe.trace.count { it == DescriptionValidationProbe.Event.Ownership(DescriptionValidationProbe.Phase.ModifierAttach, 2) })
        }
    }

    @Test
    fun c36InitialAttach() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, listOf(probe.element(1)), modifier = Modifier.Empty.then(probe.modifier(2))))
            val firstCreate = probe.trace.indexOfFirst { it is DescriptionValidationProbe.Event.Ownership }
            assertEquals(listOf(DescriptionValidationProbe.Event.Local(0), DescriptionValidationProbe.Event.ModifierValidation(2), DescriptionValidationProbe.Event.Local(1)), probe.trace.take(firstCreate))
        }
    }

    @Test
    fun c37Removal() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0, listOf(probe.element(1, key = probe.key(1)))))
            tree.update(probe.element(0))
            assertEquals(1, probe.trace.count { it == DescriptionValidationProbe.Event.Ownership(DescriptionValidationProbe.Phase.Detach, 1) })
            assertEquals(1, probe.trace.count { it == DescriptionValidationProbe.Event.Ownership(DescriptionValidationProbe.Phase.Dispose, 1) })
        }
    }

    @Test
    fun c38DetachReattach() {
        val probe = DescriptionValidationProbe()
        val source = DescriptionValidationSource(0)
        createRuntimeUiSession {
            evaluateComponentTree { Observe(source) { element(probe.element(it, key = probe.key(1))) } }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(2, 2))
            val node = probe.nodes.single()
            session.detach()
            source.publish(1)
            source.publish(2)
            session.attach()
            session.frame(Constraints.fixed(2, 2))
            assertSame(node, probe.nodes.single())
            assertEquals(2, node.id)
            assertEquals(1, source.subscriptions)
            assertEquals(0, source.releases)
            assertTrue(DescriptionValidationProbe.Event.Local(2) in probe.trace)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun c39TerminalClose() {
        val probe = DescriptionValidationProbe()
        val source = DescriptionValidationSource(0)
        val session = createRuntimeUiSession { evaluateComponentTree { Observe(source) { element(probe.element(it, key = probe.key(1))) } } }
        session.attach()
        session.frame(Constraints.fixed(2, 2))
        repeat(16) {
            source.publish(it + 1)
            session.frame(Constraints.fixed(2, 2))
        }
        session.close()
        val trace = probe.trace.toList()
        source.publish(99)
        session.close()
        assertEquals(trace, probe.trace)
        assertEquals(1, source.releases)
        assertEquals(1, probe.nodes.size)
    }

    @Test
    fun c40TerminalFailure() {
        fun failingCleanup(failure: IllegalStateException): () -> Unit = { throw failure }

        val first = IllegalStateException("last child cleanup")
        val second = IllegalStateException("first child cleanup")
        val last = IllegalStateException("root cleanup")
        val probe = DescriptionValidationProbe()
        val tree = UiTree()
        tree.update(probe.element(0, listOf(probe.element(1, key = probe.key(1), dispose = failingCleanup(second)), probe.element(2, key = probe.key(2), dispose = failingCleanup(first))), dispose = failingCleanup(last)))
        assertSame(first, assertFailsWith<IllegalStateException> { tree.close() })
        assertEquals(listOf(second, last), first.suppressedExceptions)
        val events = probe.trace.toList()
        tree.close()
        assertEquals(events, probe.trace)
        assertEquals(TreeState.Closed, tree.state)
    }

    @Test
    fun c42EqualSourceRevision() {
        val probe = DescriptionValidationProbe()
        val source = DescriptionValidationSource(0)
        createRuntimeUiSession { evaluateComponentTree { Observe(source.map { it % 2 }) { element(probe.element(it)) } } }.use { session ->
            session.attach()
            val initial = session.frame(Constraints.fixed(2, 2))
            val trace = probe.trace.toList()
            source.publish(2)
            assertSame(initial, session.frame(Constraints.fixed(2, 2)))
            assertEquals(trace, probe.trace)
        }
    }

    @Test
    fun c43NestedObservedCutoff() {
        val source = DescriptionValidationSource(0)
        val probe = DescriptionValidationProbe()
        val values = ArrayList<Pair<Int, Int>>()
        createRuntimeUiSession {
            evaluateComponentTree {
                Observe(source) { parent ->
                    Observe(source) { child ->
                        values.add(parent to child)
                        element(probe.element(child))
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(2, 2))
            source.publish(1)
            source.publish(2)
            session.frame(Constraints.fixed(2, 2))
            assertEquals(listOf(0 to 0, 2 to 2), values)
            assertEquals(listOf(0, 2), probe.trace.filterIsInstance<DescriptionValidationProbe.Event.Local>().map { it.id })
            assertEquals(1, source.subscriptions)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun c44CleanFrameAndCachedDynamicIdentity() {
        val probe = DescriptionValidationProbe()
        val source = DescriptionValidationSource(0)
        createRuntimeUiSession { evaluateComponentTree { Observe(source) { element(probe.element(it)) } } }.use { session ->
            session.attach()
            val frame = session.frame(Constraints.fixed(2, 2))
            val trace = probe.trace.toList()
            val monitor = session.startRenderMonitoring()
            try {
                repeat(16) { assertSame(frame, session.frame(Constraints.fixed(2, 2))) }
                assertEquals(trace, probe.trace)
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.ContentEvaluation])
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.RootEvaluation])
                session.frame(Constraints.fixed(3, 3))
                assertEquals(trace, probe.trace)
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.ContentEvaluation])
            } finally {
                monitor.close()
            }
        }
    }

    @Test
    fun identicalDescriptionRootReevaluationStillValidatesAllDescendants() {
        val probe = DescriptionValidationProbe()
        val state = mutableStateOf(0)
        val root: Element = probe.element(0, List(128) { probe.element(it + 1) })
        createRuntimeUiSession {
            state.value
            root
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(2, 2))
            val nodes = probe.nodes.toList()
            probe.trace.clear()
            state.value = 1
            session.frame(Constraints.fixed(2, 2))
            assertEquals((0..128).toList(), probe.trace.filterIsInstance<DescriptionValidationProbe.Event.Local>().map { it.id })
            assertEquals(nodes, probe.nodes)
            assertTrue(probe.trace.none { it is DescriptionValidationProbe.Event.Ownership })
        }
    }
}
