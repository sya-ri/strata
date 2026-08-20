package dev.s7a.strata

import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.BoxScope
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.ColumnScope
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies built-in DSL declaration order, callback lifetime, and validation boundaries.
 */
internal class LayoutDslLifetimeValidationTest {
    @Test
    fun nestedComponentsPreserveDeclarationOrderAndEmitOneRoot() {
        val first = TraceElement()
        val second = TraceElement()
        val third = TraceElement()
        val fourth = TraceElement()

        val root =
            buildUi {
                Row {
                    element(first)
                    Column {
                        element(second)
                        Box {
                            element(third)
                            Spacer()
                        }
                    }
                    element(fourth)
                }
            }

        assertEquals(3, root.children.size)
        assertSame(first, root.children[0])
        assertSame(fourth, root.children[2])
        val column = root.children[1]
        assertEquals(2, column.children.size)
        assertSame(second, column.children[0])
        val box = column.children[1]
        assertEquals(2, box.children.size)
        assertSame(third, box.children[0])
        assertEquals(0, box.children[1].children.size)
        assertSame(root, buildUi { element(root) })
    }

    @Test
    fun emptyCallbacksEmitValidComponentsWithoutChildren() {
        val row = buildUi { Row { } }
        val column = buildUi { Column { } }
        val box = buildUi { Box { } }
        val spacer = buildUi { Spacer() }

        assertTrue(row.children.isEmpty())
        assertTrue(column.children.isEmpty())
        assertTrue(box.children.isEmpty())
        assertTrue(spacer.children.isEmpty())
    }

    @Test
    fun negativeSpacingFailsBeforeTheContentCallbackRuns() {
        var rowContentRan = false
        var columnContentRan = false

        assertThrows(IllegalArgumentException::class.java) {
            buildUi {
                Row(spacing = -1) { rowContentRan = true }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildUi {
                Column(spacing = -1) { columnContentRan = true }
            }
        }

        assertFalse(rowContentRan)
        assertFalse(columnContentRan)
    }

    @Test
    fun caughtChildCallbackFailuresPreserveIdentityAndEmitNoPartialComponent() {
        val rowFailure = IllegalStateException("row failure")
        val columnFailure = IllegalArgumentException("column failure")
        val boxFailure = UnsupportedOperationException("box failure")
        val survivor = TraceElement()

        val root =
            buildUi {
                Row {
                    try {
                        emitFailingRow(rowFailure)
                    } catch (error: Throwable) {
                        assertSame(rowFailure, error)
                    }
                    try {
                        emitFailingColumn(columnFailure)
                    } catch (error: Throwable) {
                        assertSame(columnFailure, error)
                    }
                    try {
                        emitFailingBox(boxFailure)
                    } catch (error: Throwable) {
                        assertSame(boxFailure, error)
                    }
                    element(survivor)
                }
            }

        assertEquals(listOf(survivor), root.children)
    }

    @Test
    fun escapedOuterScopeRejectsEveryBuilderBeforeItsCallback() {
        var capturedScope: UiScope? = null
        buildUi {
            capturedScope = this
            Row { }
        }
        val scope = requireNotNull(capturedScope)
        val ran = mutableListOf<Boolean>()
        val builders =
            listOf<(UiScope, () -> Unit) -> Unit>(
                { current, callback -> current.Row { callback() } },
                { current, callback -> current.Column { callback() } },
                { current, callback -> current.Box { callback() } },
                { current, _ -> current.Spacer() },
            )

        builders.forEach { builder ->
            var callbackRan = false
            assertThrows(IllegalStateException::class.java) {
                builder(scope) { callbackRan = true }
            }
            ran += callbackRan
        }

        assertEquals(listOf(false, false, false, false), ran)
    }

    @Test
    fun receiverModifiersRejectPostCallbackUseForEveryScope() {
        var rowScope: RowScope? = null
        var columnScope: ColumnScope? = null
        var boxScope: BoxScope? = null
        buildUi { Row { rowScope = this } }
        buildUi { Column { columnScope = this } }
        buildUi { Box { boxScope = this } }

        val operations =
            listOf<() -> Unit>(
                { with(requireNotNull(rowScope)) { Modifier.Empty.weight(1f) } },
                { with(requireNotNull(rowScope)) { Modifier.Empty.align(VerticalAlignment.Bottom) } },
                { with(requireNotNull(columnScope)) { Modifier.Empty.weight(1f) } },
                { with(requireNotNull(columnScope)) { Modifier.Empty.align(HorizontalAlignment.End) } },
                { with(requireNotNull(boxScope)) { Modifier.Empty.align(Alignment.Center) } },
            )

        operations.forEach { operation ->
            assertThrows(IllegalStateException::class.java) { operation() }
        }
    }

    @Test
    fun receiverModifiersRejectWrongThreadUseWhileEveryScopeIsActive() {
        assertWrongThreadRowModifiers()
        assertWrongThreadColumnModifiers()
        assertWrongThreadBoxModifiers()
    }

    @Test
    fun invalidWeightsFailBeforeAppendingForBothLinearScopes() {
        val invalidWeights =
            listOf(
                0f,
                -1f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )

        buildUi {
            Row {
                invalidWeights.forEach { weight ->
                    assertThrows(IllegalArgumentException::class.java) {
                        Modifier.Empty.weight(weight)
                    }
                }
                element(TraceElement())
            }
        }
        buildUi {
            Column {
                invalidWeights.forEach { weight ->
                    assertThrows(IllegalArgumentException::class.java) {
                        Modifier.Empty.weight(weight)
                    }
                }
                element(TraceElement())
            }
        }
    }

    private fun assertWrongThreadRowModifiers() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = ArrayList<Throwable>()
        lateinit var worker: Thread
        buildUi {
            Row {
                val scope = this
                worker =
                    Thread {
                        try {
                            entered.await()
                            try {
                                with(scope) { Modifier.Empty.weight(1f) }
                            } catch (error: Throwable) {
                                failures += error
                            }
                            try {
                                with(scope) { Modifier.Empty.align(VerticalAlignment.Bottom) }
                            } catch (error: Throwable) {
                                failures += error
                            }
                        } finally {
                            finished.countDown()
                        }
                    }
                worker.start()
                entered.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                element(TraceElement())
            }
        }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertWrongThreadFailures(failures, 2)
    }

    private fun assertWrongThreadColumnModifiers() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = ArrayList<Throwable>()
        lateinit var worker: Thread
        buildUi {
            Column {
                val scope = this
                worker =
                    Thread {
                        try {
                            entered.await()
                            try {
                                with(scope) { Modifier.Empty.weight(1f) }
                            } catch (error: Throwable) {
                                failures += error
                            }
                            try {
                                with(scope) { Modifier.Empty.align(HorizontalAlignment.End) }
                            } catch (error: Throwable) {
                                failures += error
                            }
                        } finally {
                            finished.countDown()
                        }
                    }
                worker.start()
                entered.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                element(TraceElement())
            }
        }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertWrongThreadFailures(failures, 2)
    }

    private fun assertWrongThreadBoxModifiers() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = ArrayList<Throwable>()
        lateinit var worker: Thread
        buildUi {
            Box {
                val scope = this
                worker =
                    Thread {
                        try {
                            entered.await()
                            try {
                                with(scope) { Modifier.Empty.align(Alignment.Center) }
                            } catch (error: Throwable) {
                                failures += error
                            }
                        } finally {
                            finished.countDown()
                        }
                    }
                worker.start()
                entered.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                element(TraceElement())
            }
        }
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertWrongThreadFailures(failures, 1)
    }

    private fun assertWrongThreadFailures(
        failures: List<Throwable>,
        expectedCount: Int,
    ) {
        assertEquals(expectedCount, failures.size)
        assertTrue(failures.all { error -> error is IllegalStateException })
        failures.forEach { error ->
            assertEquals("UiScope can only be used from its constructing thread.", error.message)
        }
    }

    private fun UiScope.emitFailingRow(failure: Throwable) {
        Row {
            element(TraceElement())
            throw failure
        }
    }

    private fun UiScope.emitFailingColumn(failure: Throwable) {
        Column {
            element(TraceElement())
            throw failure
        }
    }

    private fun UiScope.emitFailingBox(failure: Throwable) {
        Box {
            element(TraceElement())
            throw failure
        }
    }

    private class TraceNode : Node()

    private class TraceElement : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<TraceElement, TraceNode> =
                ElementType(
                    elementClass = TraceElement::class,
                    nodeClass = TraceNode::class,
                    validateLocal = { },
                    createNode = { TraceNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
