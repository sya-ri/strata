@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that overflowing linear children remain paintable and reachable by input.
 */
internal class LayoutOverflowIntegrationTest {
    @Test
    fun everyRowArrangementDegradesToStartForNegativeSlack() {
        Arrangement.entries.forEach { arrangement ->
            assertRowOverflow(arrangement)
        }
    }

    @Test
    fun everyColumnArrangementDegradesToStartForNegativeSlack() {
        Arrangement.entries.forEach { arrangement ->
            assertColumnOverflow(arrangement)
        }
    }

    @Test
    fun flowRowHeightOverflowDoesNotClipPaintingOrPointerInput() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                FlowRow(horizontalSpacing = 1, verticalSpacing = 2) {
                    element(ExternalElement(probe = probe, width = 6, height = 3, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = probe, width = 5, height = 4, nodeId = ExternalNodeId.Modifier))
                }
            },
        )

        assertEquals(IntSize(6, 4), tree.measure(Constraints(maxWidth = 8, maxHeight = 4)))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 6, 3), IntRect(0, 5, 5, 9)), paintBounds(tree))
        assertMeasuredAndPlacedAndPainted(probe, ExternalNodeId.Child)
        assertMeasuredAndPlacedAndPainted(probe, ExternalNodeId.Modifier)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 6), PointerButton.Primary)))
        assertEquals(0, requireNotNull(probe.componentNodes[ExternalNodeId.Child]).presses)
        assertEquals(1, requireNotNull(probe.componentNodes[ExternalNodeId.Modifier]).presses)
        tree.close()
    }

    private fun assertRowOverflow(arrangement: Arrangement) {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Row(
                    spacing = 2,
                    horizontalArrangement = arrangement,
                ) {
                    element(ExternalElement(probe = probe, width = 8, height = 2, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = probe, width = 9, height = 3, nodeId = ExternalNodeId.Modifier))
                }
            },
        )
        assertEquals(IntSize(10, 3), tree.measure(Constraints(minWidth = 10, maxWidth = 10, maxHeight = 10)))
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 8, 2), IntRect(10, 0, 19, 3)),
            paintBounds(tree),
        )
        assertMeasuredAndPlacedAndPainted(probe, ExternalNodeId.Child)
        assertMeasuredAndPlacedAndPainted(probe, ExternalNodeId.Modifier)

        val event = PointerEvent.Press(IntOffset(15, 1), PointerButton.Primary)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(event))
        assertEquals(IntOffset(15, 1), event.position)
        assertEquals(0, requireNotNull(probe.componentNodes[ExternalNodeId.Child]).presses)
        assertEquals(1, requireNotNull(probe.componentNodes[ExternalNodeId.Modifier]).presses)
        tree.close()
    }

    private fun assertColumnOverflow(arrangement: Arrangement) {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Column(
                    spacing = 2,
                    verticalArrangement = arrangement,
                ) {
                    element(ExternalElement(probe = probe, width = 2, height = 8, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = probe, width = 3, height = 9, nodeId = ExternalNodeId.Modifier))
                }
            },
        )
        assertEquals(IntSize(3, 10), tree.measure(Constraints(maxWidth = 10, minHeight = 10, maxHeight = 10)))
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 2, 8), IntRect(0, 10, 3, 19)),
            paintBounds(tree),
        )
        assertMeasuredAndPlacedAndPainted(probe, ExternalNodeId.Child)
        assertMeasuredAndPlacedAndPainted(probe, ExternalNodeId.Modifier)

        val event = PointerEvent.Press(IntOffset(1, 15), PointerButton.Primary)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(event))
        assertEquals(IntOffset(1, 15), event.position)
        assertEquals(0, requireNotNull(probe.componentNodes[ExternalNodeId.Child]).presses)
        assertEquals(1, requireNotNull(probe.componentNodes[ExternalNodeId.Modifier]).presses)
        tree.close()
    }

    private fun paintBounds(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }

    private fun assertMeasuredAndPlacedAndPainted(
        probe: ExternalProbe,
        nodeId: ExternalNodeId,
    ) {
        val node = requireNotNull(probe.componentNodes[nodeId])
        assertEquals(1, node.measures)
        assertEquals(1, node.layouts)
        assertEquals(1, node.paints)
    }
}
