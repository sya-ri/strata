package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the exact declaration benchmark inputs through retained pipelines and terminal cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ModifierDeclarationParityTest {
    @Test
    fun measuredDeclarationsKeepParityAcrossRebuildRemovalAndClose() {
        for (length in listOf(0, 1, 8, 32, 128)) {
            val descriptions =
                List(length) {
                    Modifier.Empty
                        .padding(it % 4)
                        .elements()
                        .single()
                }
            val side = 2 * (0 until length).sumOf { it % 4 }
            val expectedSize = IntSize(side, side * CHILD_COUNT)
            val nodeCount = 1 + CHILD_COUNT * (length + 1)
            val tree = UiTree()
            val monitor = tree.startRenderMonitoring(nodeCount)
            try {
                tree.update(declaration(descriptions))
                assertFrame(tree, expectedSize)
                val initial = monitor.snapshot()
                assertFalse(initial.overflowed)
                assertEquals(nodeCount.toLong(), initial.counts[UiRenderMetric.NodeCreate])
                assertEquals(nodeCount, initial.nodes.size)

                tree.update(declaration(descriptions))
                assertFrame(tree, expectedSize)
                val rebuilt = monitor.snapshot()
                assertEquals(initial.nodes.map { it.id }, rebuilt.nodes.map { it.id })
                assertEquals(nodeCount.toLong(), rebuilt.counts[UiRenderMetric.NodeCreate])
                assertEquals(0L, rebuilt.counts[UiRenderMetric.NodeDispose])

                tree.update(declaration(emptyList()))
                assertFrame(tree, IntSize.Zero)
                val removed = monitor.snapshot()
                assertFalse(removed.overflowed)
                assertEquals((length * CHILD_COUNT).toLong(), removed.counts[UiRenderMetric.NodeDispose])
                assertEquals(length * CHILD_COUNT, removed.nodes.count { it.retired })
                assertEquals(1 + CHILD_COUNT, removed.nodes.count { it.retired.not() })
                assertEquals(0, removed.activeSubscriptions)
            } finally {
                tree.close()
                monitor.close()
            }
            assertEquals(TreeState.Closed, tree.state)
            assertNull(tree.monitoring.collector)
        }
    }

    private fun declaration(descriptions: List<ModifierElement>): Element =
        evaluateComponentTree {
            Column {
                repeat(CHILD_COUNT) {
                    var modifier = Modifier.Empty
                    for (description in descriptions) modifier = modifier.then(description)
                    Spacer(modifier = modifier)
                }
            }
        }

    private fun assertFrame(
        tree: UiTree,
        expectedSize: IntSize,
    ) {
        assertEquals(expectedSize, tree.measure(Constraints()))
        tree.layout()
        assertTrue(tree.paint().isEmpty())
        assertTrue(tree.semantics().isEmpty())
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
    }

    private companion object {
        const val CHILD_COUNT = 128
    }
}
