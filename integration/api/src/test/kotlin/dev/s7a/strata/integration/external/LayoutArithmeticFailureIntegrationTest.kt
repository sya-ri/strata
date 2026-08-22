@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies checked natural-size arithmetic failure and descendant-first cleanup.
 */
internal class LayoutArithmeticFailureIntegrationTest {
    @Test
    fun unboundedLinearNaturalOverflowPoisonsAndCleansEachAxis() {
        listOf(ArithmeticAxis.Horizontal, ArithmeticAxis.Vertical).forEach { axis ->
            assertArithmeticFailure(axis)
        }
    }

    private fun assertArithmeticFailure(axis: ArithmeticAxis) {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(buildArithmeticRoot(axis, probe))
        val firstNode = requireNotNull(probe.componentNodes[ExternalNodeId.Child])
        val secondNode = requireNotNull(probe.componentNodes[ExternalNodeId.Modifier])

        assertThrows<ArithmeticException> { tree.measure(Constraints()) }
        assertEquals(
            listOf(ExternalNodeId.Child, ExternalNodeId.Modifier),
            probe.componentMeasureOrder,
        )
        assertEquals(1, firstNode.measures)
        assertEquals(1, secondNode.measures)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Child),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Child),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Child),
            ),
            probe.lifecycle,
        )
        assertThrows<IllegalStateException> { tree.measure(Constraints()) }
        assertThrows<IllegalStateException> { tree.layout() }
        assertThrows<IllegalStateException> { tree.paint() }
        assertThrows<IllegalStateException> {
            tree.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary))
        }
        assertThrows<IllegalStateException> { firstNode.invalidateForTest(DirtyPhase.Paint) }
        assertThrows<IllegalStateException> { secondNode.invalidateForTest(DirtyPhase.Paint) }

        val lifecycleAfterFailure = probe.lifecycle.toList()
        tree.close()
        assertEquals(TreeState.Closed, tree.state)
        assertEquals(lifecycleAfterFailure, probe.lifecycle)
        tree.close()
        assertEquals(lifecycleAfterFailure, probe.lifecycle)
        assertThrows<IllegalStateException> { firstNode.invalidateForTest(DirtyPhase.Paint) }
        assertThrows<IllegalStateException> { secondNode.invalidateForTest(DirtyPhase.Paint) }
    }

    private fun buildArithmeticRoot(
        axis: ArithmeticAxis,
        probe: ExternalProbe,
    ): Element =
        when (axis) {
            ArithmeticAxis.Horizontal -> {
                evaluateComponentTree {
                    Row {
                        hugeChildren(probe).forEach(::element)
                    }
                }
            }

            ArithmeticAxis.Vertical -> {
                evaluateComponentTree {
                    Column {
                        hugeChildren(probe).forEach(::element)
                    }
                }
            }
        }

    private fun hugeChildren(probe: ExternalProbe): List<ExternalElement> =
        listOf(
            ExternalElement(
                probe = probe,
                width = Int.MAX_VALUE,
                height = Int.MAX_VALUE,
                nodeId = ExternalNodeId.Child,
            ),
            ExternalElement(
                probe = probe,
                width = Int.MAX_VALUE,
                height = Int.MAX_VALUE,
                nodeId = ExternalNodeId.Modifier,
            ),
        )

    private sealed interface ArithmeticAxis {
        data object Horizontal : ArithmeticAxis

        data object Vertical : ArithmeticAxis
    }
}
