package dev.s7a.strata.integration.external

import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies all main-axis arrangements, spacing, and child cardinalities with external primitives.
 */
internal class LayoutArrangementIntegrationTest {
    @Test
    fun rowArrangementsUseAbsoluteOffsetsWithOddSlack() {
        val children =
            listOf(
                ChildSpec(3, 2, ExternalNodeId.Child),
                ChildSpec(4, 3, ExternalNodeId.Modifier),
            )
        val expected =
            mapOf(
                Arrangement.Start to listOf(IntRect(0, 0, 3, 2), IntRect(3, 0, 7, 3)),
                Arrangement.Center to listOf(IntRect(4, 0, 7, 2), IntRect(7, 0, 11, 3)),
                Arrangement.End to listOf(IntRect(9, 0, 12, 2), IntRect(12, 0, 16, 3)),
                Arrangement.SpaceBetween to listOf(IntRect(0, 0, 3, 2), IntRect(12, 0, 16, 3)),
                Arrangement.SpaceAround to listOf(IntRect(2, 0, 5, 2), IntRect(9, 0, 13, 3)),
                Arrangement.SpaceEvenly to listOf(IntRect(3, 0, 6, 2), IntRect(9, 0, 13, 3)),
            )

        Arrangement.entries.forEach { arrangement ->
            assertEquals(expected.getValue(arrangement), rowBounds(arrangement, children))
        }
    }

    @Test
    fun columnArrangementsUseAbsoluteOffsetsWithOddSlack() {
        val children =
            listOf(
                ChildSpec(2, 3, ExternalNodeId.Child),
                ChildSpec(3, 4, ExternalNodeId.Modifier),
            )
        val expected =
            mapOf(
                Arrangement.Start to listOf(IntRect(0, 0, 2, 3), IntRect(0, 3, 3, 7)),
                Arrangement.Center to listOf(IntRect(0, 4, 2, 7), IntRect(0, 7, 3, 11)),
                Arrangement.End to listOf(IntRect(0, 9, 2, 12), IntRect(0, 12, 3, 16)),
                Arrangement.SpaceBetween to listOf(IntRect(0, 0, 2, 3), IntRect(0, 12, 3, 16)),
                Arrangement.SpaceAround to listOf(IntRect(0, 2, 2, 5), IntRect(0, 9, 3, 13)),
                Arrangement.SpaceEvenly to listOf(IntRect(0, 3, 2, 6), IntRect(0, 9, 3, 13)),
            )

        Arrangement.entries.forEach { arrangement ->
            assertEquals(expected.getValue(arrangement), columnBounds(arrangement, children))
        }
    }

    @Test
    fun fixedSpacingComposesWithRowAndColumnChildExtents() {
        val children =
            listOf(
                ChildSpec(3, 2, ExternalNodeId.Child),
                ChildSpec(4, 3, ExternalNodeId.Modifier),
            )

        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(5, 0, 9, 3)),
            rowBounds(Arrangement.Start, children, spacing = 2),
        )
        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(12, 0, 16, 3)),
            rowBounds(Arrangement.SpaceBetween, children, spacing = 2),
        )
        assertEquals(
            listOf(IntRect(0, 0, 2, 3), IntRect(0, 5, 3, 9)),
            columnBounds(Arrangement.Start, children.map { child -> child.copy(width = child.height, height = child.width) }, spacing = 2),
        )
        assertEquals(
            listOf(IntRect(0, 0, 2, 3), IntRect(0, 12, 3, 16)),
            columnBounds(
                Arrangement.SpaceBetween,
                children.map { child -> child.copy(width = child.height, height = child.width) },
                spacing = 2,
            ),
        )
    }

    @Test
    fun publicDefaultArrangementsPlaceLinearChildrenAtTheStart() {
        val children =
            listOf(
                ChildSpec(3, 2, ExternalNodeId.Child),
                ChildSpec(4, 3, ExternalNodeId.Modifier),
            )

        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(3, 0, 7, 3)),
            defaultRowBounds(children),
        )
        assertEquals(
            listOf(IntRect(0, 0, 2, 3), IntRect(0, 3, 3, 7)),
            defaultColumnBounds(children.map { child -> child.copy(width = child.height, height = child.width) }),
        )
    }

    @Test
    fun everyArrangementHandlesZeroAndOneChild() {
        val oneRowChild = listOf(ChildSpec(3, 2, ExternalNodeId.Child))
        val oneColumnChild = listOf(ChildSpec(2, 3, ExternalNodeId.Child))
        val expectedRow =
            mapOf(
                Arrangement.Start to IntRect(0, 0, 3, 2),
                Arrangement.Center to IntRect(6, 0, 9, 2),
                Arrangement.End to IntRect(13, 0, 16, 2),
                Arrangement.SpaceBetween to IntRect(0, 0, 3, 2),
                Arrangement.SpaceAround to IntRect(6, 0, 9, 2),
                Arrangement.SpaceEvenly to IntRect(6, 0, 9, 2),
            )
        val expectedColumn =
            mapOf(
                Arrangement.Start to IntRect(0, 0, 2, 3),
                Arrangement.Center to IntRect(0, 6, 2, 9),
                Arrangement.End to IntRect(0, 13, 2, 16),
                Arrangement.SpaceBetween to IntRect(0, 0, 2, 3),
                Arrangement.SpaceAround to IntRect(0, 6, 2, 9),
                Arrangement.SpaceEvenly to IntRect(0, 6, 2, 9),
            )
        Arrangement.entries.forEach { arrangement ->
            assertEquals(emptyList<IntRect>(), rowBounds(arrangement, emptyList()))
            assertEquals(listOf(expectedRow.getValue(arrangement)), rowBounds(arrangement, oneRowChild))
            assertEquals(emptyList<IntRect>(), columnBounds(arrangement, emptyList()))
            assertEquals(listOf(expectedColumn.getValue(arrangement)), columnBounds(arrangement, oneColumnChild))
        }
    }

    private fun rowBounds(
        arrangement: Arrangement,
        children: List<ChildSpec>,
        spacing: Int = 0,
    ): List<IntRect> {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            buildUi {
                Row(horizontalArrangement = arrangement, spacing = spacing) {
                    children.forEach { child -> element(child.element(probe)) }
                }
            },
        )
        assertEquals(IntSize(16, 5), tree.measure(Constraints.fixed(width = 16, height = 5)))
        tree.layout()
        val bounds = paintBounds(tree)
        assertMeasuredAndPlaced(probe, children)
        tree.close()
        return bounds
    }

    private fun defaultRowBounds(children: List<ChildSpec>): List<IntRect> {
        val tree = UiTree()
        tree.update(
            buildUi {
                Row {
                    children.forEach { child -> element(child.element(ExternalProbe())) }
                }
            },
        )
        tree.measure(Constraints.fixed(width = 16, height = 5))
        tree.layout()
        val bounds = paintBounds(tree)
        tree.close()
        return bounds
    }

    private fun columnBounds(
        arrangement: Arrangement,
        children: List<ChildSpec>,
        spacing: Int = 0,
    ): List<IntRect> {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            buildUi {
                Column(verticalArrangement = arrangement, spacing = spacing) {
                    children.forEach { child -> element(child.element(probe)) }
                }
            },
        )
        assertEquals(IntSize(5, 16), tree.measure(Constraints.fixed(width = 5, height = 16)))
        tree.layout()
        val bounds = paintBounds(tree)
        assertMeasuredAndPlaced(probe, children)
        tree.close()
        return bounds
    }

    private fun defaultColumnBounds(children: List<ChildSpec>): List<IntRect> {
        val tree = UiTree()
        tree.update(
            buildUi {
                Column {
                    children.forEach { child -> element(child.element(ExternalProbe())) }
                }
            },
        )
        tree.measure(Constraints.fixed(width = 5, height = 16))
        tree.layout()
        val bounds = paintBounds(tree)
        tree.close()
        return bounds
    }

    private fun paintBounds(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }

    private fun assertMeasuredAndPlaced(
        probe: ExternalProbe,
        children: List<ChildSpec>,
    ) {
        children.forEach { child ->
            val node = requireNotNull(probe.componentNodes[child.nodeId])
            assertEquals(1, node.measures)
            assertEquals(1, node.layouts)
        }
    }

    private data class ChildSpec(
        val width: Int,
        val height: Int,
        val nodeId: ExternalNodeId,
    ) {
        fun element(probe: ExternalProbe): ExternalElement =
            ExternalElement(
                probe = probe,
                width = width,
                height = height,
                nodeId = nodeId,
            )
    }
}
