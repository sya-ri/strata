package dev.s7a.strata

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ColumnScope
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.GridScope
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.RowScope
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.StackScope
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
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
            buildComponentTree {
                Row {
                    element(first)
                    Column {
                        element(second)
                        Stack {
                            element(third)
                            Grid(columns = 1) { Spacer() }
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
        val stack = column.children[1]
        assertEquals(2, stack.children.size)
        assertSame(third, stack.children[0])
        assertEquals(
            0,
            stack.children[1]
                .children
                .single()
                .children.size,
        )
        assertSame(root, buildComponentTree { element(root) })
    }

    @Test
    fun emptyCallbacksEmitValidComponentsWithoutChildren() {
        val row = buildComponentTree { Row { } }
        val column = buildComponentTree { Column { } }
        val stack = buildComponentTree { Stack { } }
        val grid = buildComponentTree { Grid(columns = 1) { } }
        val spacer = buildComponentTree { Spacer() }

        assertTrue(row.children.isEmpty())
        assertTrue(column.children.isEmpty())
        assertTrue(stack.children.isEmpty())
        assertTrue(grid.children.isEmpty())
        assertTrue(spacer.children.isEmpty())
    }

    @Test
    fun negativeSpacingFailsBeforeTheContentCallbackRuns() {
        var rowContentRan = false
        var columnContentRan = false
        var gridContentRan = false

        assertThrows(IllegalArgumentException::class.java) {
            buildComponentTree {
                Row(spacing = -1) { rowContentRan = true }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildComponentTree {
                Column(spacing = -1) { columnContentRan = true }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildComponentTree {
                Grid(columns = 0) { gridContentRan = true }
            }
        }

        assertFalse(rowContentRan)
        assertFalse(columnContentRan)
        assertFalse(gridContentRan)
    }

    @Test
    fun caughtChildCallbackFailuresPreserveIdentityAndEmitNoPartialComponent() {
        val rowFailure = IllegalStateException("row failure")
        val columnFailure = IllegalArgumentException("column failure")
        val stackFailure = UnsupportedOperationException("stack failure")
        val survivor = TraceElement()

        val root =
            buildComponentTree {
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
                        emitFailingStack(stackFailure)
                    } catch (error: Throwable) {
                        assertSame(stackFailure, error)
                    }
                    element(survivor)
                }
            }

        assertEquals(listOf(survivor), root.children)
    }

    @Test
    fun escapedOuterScopeRejectsEveryBuilderBeforeItsCallback() {
        var capturedScope: UiScope? = null
        buildComponentTree {
            capturedScope = this
            Row { }
        }
        val scope = requireNotNull(capturedScope)
        val ran = mutableListOf<Boolean>()
        val builders =
            listOf<(UiScope, () -> Unit) -> Unit>(
                { current, callback -> current.Row { callback() } },
                { current, callback -> current.Column { callback() } },
                { current, callback -> current.Stack { callback() } },
                { current, callback -> current.Grid(columns = 1) { callback() } },
                { current, _ -> current.Spacer() },
            )

        builders.forEach { builder ->
            var callbackRan = false
            assertThrows(IllegalStateException::class.java) {
                builder(scope) { callbackRan = true }
            }
            ran += callbackRan
        }

        assertEquals(listOf(false, false, false, false, false), ran)
    }

    @Test
    fun receiverModifiersRejectPostCallbackUseForEveryScope() {
        var rowScope: RowScope? = null
        var columnScope: ColumnScope? = null
        var stackScope: StackScope? = null
        var gridScope: GridScope? = null
        buildComponentTree { Row { rowScope = this } }
        buildComponentTree { Column { columnScope = this } }
        buildComponentTree { Stack { stackScope = this } }
        buildComponentTree { Grid(columns = 1) { gridScope = this } }

        val operations =
            listOf<() -> Unit>(
                { with(requireNotNull(rowScope)) { Modifier.Empty.weight(1f) } },
                { with(requireNotNull(rowScope)) { Modifier.Empty.align(VerticalAlignment.Bottom) } },
                { with(requireNotNull(columnScope)) { Modifier.Empty.weight(1f) } },
                { with(requireNotNull(columnScope)) { Modifier.Empty.align(HorizontalAlignment.End) } },
                { with(requireNotNull(stackScope)) { Modifier.Empty.align(Alignment.Center) } },
                { with(requireNotNull(gridScope)) { Modifier.Empty.align(Alignment.Center) } },
            )

        operations.forEach { operation ->
            assertThrows(IllegalStateException::class.java) { operation() }
        }
    }

    @Test
    fun receiverModifiersRejectWrongThreadUseWhileEveryScopeIsActive() {
        assertWrongThreadRowModifiers()
        assertWrongThreadColumnModifiers()
        assertWrongThreadStackModifiers()
        assertWrongThreadGridModifiers()
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

        buildComponentTree {
            Row {
                invalidWeights.forEach { weight ->
                    assertThrows(IllegalArgumentException::class.java) {
                        Modifier.Empty.weight(weight)
                    }
                }
                element(TraceElement())
            }
        }
        buildComponentTree {
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
        buildComponentTree {
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
        buildComponentTree {
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

    private fun assertWrongThreadStackModifiers() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = ArrayList<Throwable>()
        lateinit var worker: Thread
        buildComponentTree {
            Stack {
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

    private fun assertWrongThreadGridModifiers() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = ArrayList<Throwable>()
        lateinit var worker: Thread
        buildComponentTree {
            Grid(columns = 1) {
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

    private fun UiScope.emitFailingStack(failure: Throwable) {
        Stack {
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
