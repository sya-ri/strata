package dev.s7a.strata.integration.external

import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.ColumnScope
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies bounded and unbounded weighted allocation with external primitive children.
 */
internal class WeightedLayoutIntegrationTest {
    @Test
    fun boundedRowMeasuresFixedContentFirstAndAllocatesWeightedSlots() {
        val fixture =
            rowFixture(spacing = 2) { probe ->
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 1, 3))
                element(ExternalElement(probe = probe, width = 4, height = 2, nodeId = ExternalNodeId.Root))
                element(weightedElement(probe, ExternalNodeId.Modifier, 2f, 1, 3))
            }
        val constraints = Constraints.fixed(width = 20, height = 10)

        assertEquals(IntSize(20, 10), fixture.tree.measure(constraints))
        assertEquals(
            listOf(ExternalNodeId.Root, ExternalNodeId.Child, ExternalNodeId.Modifier),
            fixture.probe.componentMeasureOrder,
        )
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = 20, minHeight = 0, maxHeight = 10),
                Constraints.fixed(width = 4, height = 10).copy(minHeight = 0),
                Constraints.fixed(width = 8, height = 10).copy(minHeight = 0),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 4, 3), IntRect(6, 0, 10, 2), IntRect(12, 0, 20, 3)),
            paintBounds(fixture.tree),
        )
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Root, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    @Test
    fun boundedColumnUsesTheSameExactSlotContract() {
        val fixture =
            columnFixture(spacing = 2) { probe ->
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 2, 3))
                element(ExternalElement(probe = probe, width = 4, height = 4, nodeId = ExternalNodeId.Root))
                element(weightedElement(probe, ExternalNodeId.Modifier, 2f, 2, 3))
            }
        val constraints = Constraints.fixed(width = 10, height = 20)

        assertEquals(IntSize(10, 20), fixture.tree.measure(constraints))
        assertEquals(
            listOf(ExternalNodeId.Root, ExternalNodeId.Child, ExternalNodeId.Modifier),
            fixture.probe.componentMeasureOrder,
        )
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = 10, minHeight = 0, maxHeight = 20),
                Constraints(minWidth = 0, maxWidth = 10, minHeight = 4, maxHeight = 4),
                Constraints(minWidth = 0, maxWidth = 10, minHeight = 8, maxHeight = 8),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 2, 4), IntRect(0, 6, 4, 10), IntRect(0, 12, 2, 20)),
            paintBounds(fixture.tree),
        )
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Root, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    @Test
    fun equalWeightsFloorNonLastSlotsAndGiveResidueToTheLastChild() {
        val fixture =
            rowFixture { probe ->
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 1, 2))
                element(weightedElement(probe, ExternalNodeId.Root, 1f, 1, 2))
                element(weightedElement(probe, ExternalNodeId.Modifier, 1f, 1, 2))
            }

        assertEquals(IntSize(10, 5), fixture.tree.measure(Constraints.fixed(width = 10, height = 5)))
        assertEquals(
            listOf(
                Constraints.fixed(width = 3, height = 5).copy(minHeight = 0),
                Constraints.fixed(width = 3, height = 5).copy(minHeight = 0),
                Constraints.fixed(width = 4, height = 5).copy(minHeight = 0),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(3, 0, 6, 2), IntRect(6, 0, 10, 2)),
            paintBounds(fixture.tree),
        )
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Root, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    @Test
    fun nonFillingChildrenUseActualSizesForNaturalExtentAndPlacement() {
        val fixture =
            rowFixture(spacing = 2) { probe ->
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 3, 2, fill = false))
                element(weightedElement(probe, ExternalNodeId.Modifier, 1f, 4, 3, fill = false))
            }

        assertEquals(IntSize(9, 3), fixture.tree.measure(Constraints(maxWidth = 20, maxHeight = 10)))
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = 9, minHeight = 0, maxHeight = 10),
                Constraints(minWidth = 0, maxWidth = 9, minHeight = 0, maxHeight = 10),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertEquals(listOf(IntRect(0, 0, 3, 2), IntRect(5, 0, 9, 3)), paintBounds(fixture.tree))
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    @Test
    fun exhaustedFixedSpaceGivesAWeightedChildAnExactZeroSlot() {
        val fixture =
            rowFixture(spacing = 2) { probe ->
                element(ExternalElement(probe = probe, width = 10, height = 2, nodeId = ExternalNodeId.Root))
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 6, 2))
            }

        assertEquals(IntSize(12, 5), fixture.tree.measure(Constraints.fixed(width = 12, height = 5)))
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = 12, minHeight = 0, maxHeight = 5),
                Constraints.fixed(width = 0, height = 5).copy(minHeight = 0),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertEquals(listOf(IntRect(0, 0, 10, 2), IntRect(12, 0, 12, 2)), paintBounds(fixture.tree))
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Root, ExternalNodeId.Child)
        fixture.tree.close()
    }

    @Test
    fun unboundedRowIgnoresWeightSlotsAndPreservesParentMinimums() {
        val fixture =
            rowFixture { probe ->
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 3, 2))
                element(weightedElement(probe, ExternalNodeId.Modifier, 2f, 5, 4, fill = false))
            }
        val constraints = Constraints(minWidth = 7, maxWidth = Int.MAX_VALUE, minHeight = 6, maxHeight = 10)

        assertEquals(IntSize(8, 6), fixture.tree.measure(constraints))
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = Int.MAX_VALUE, minHeight = 0, maxHeight = 10),
                Constraints(minWidth = 0, maxWidth = Int.MAX_VALUE, minHeight = 0, maxHeight = 10),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    @Test
    fun unboundedColumnIgnoresFillAndUsesAnUnboundedMainConstraint() {
        val fixture =
            columnFixture { probe ->
                element(weightedElement(probe, ExternalNodeId.Child, 1f, 2, 3))
                element(weightedElement(probe, ExternalNodeId.Modifier, 2f, 4, 5, fill = false))
            }
        val constraints = Constraints(minWidth = 6, maxWidth = 10, minHeight = 7, maxHeight = Int.MAX_VALUE)

        assertEquals(IntSize(6, 8), fixture.tree.measure(constraints))
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = 10, minHeight = 0, maxHeight = Int.MAX_VALUE),
                Constraints(minWidth = 0, maxWidth = 10, minHeight = 0, maxHeight = Int.MAX_VALUE),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    @Test
    fun innermostWeightProviderWinsOnOneDirectChild() {
        val fixture =
            rowFixture { probe ->
                element(
                    ExternalElement(
                        probe = probe,
                        width = 1,
                        height = 2,
                        nodeId = ExternalNodeId.Child,
                        modifier = Modifier.Empty.weight(1f).weight(3f),
                    ),
                )
                element(weightedElement(probe, ExternalNodeId.Modifier, 1f, 1, 2))
            }

        assertEquals(IntSize(12, 3), fixture.tree.measure(Constraints.fixed(width = 12, height = 3)))
        assertEquals(
            listOf(
                Constraints.fixed(width = 9, height = 3).copy(minHeight = 0),
                Constraints.fixed(width = 3, height = 3).copy(minHeight = 0),
            ),
            fixture.probe.componentMeasureConstraints,
        )
        fixture.tree.layout()
        assertEquals(listOf(IntRect(0, 0, 9, 2), IntRect(9, 0, 12, 2)), paintBounds(fixture.tree))
        assertMeasuredAndPlaced(fixture.probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        fixture.tree.close()
    }

    private fun rowFixture(
        spacing: Int = 0,
        content: RowScope.(ExternalProbe) -> Unit,
    ): Fixture =
        Fixture(ExternalProbe()).also { fixture ->
            fixture.tree.update(
                buildUi {
                    Row(spacing = spacing) { content(fixture.probe) }
                },
            )
        }

    private fun columnFixture(
        spacing: Int = 0,
        content: ColumnScope.(ExternalProbe) -> Unit,
    ): Fixture =
        Fixture(ExternalProbe()).also { fixture ->
            fixture.tree.update(
                buildUi {
                    Column(spacing = spacing) { content(fixture.probe) }
                },
            )
        }

    private fun RowScope.weightedElement(
        probe: ExternalProbe,
        nodeId: ExternalNodeId,
        weight: Float,
        width: Int,
        height: Int,
        fill: Boolean = true,
    ): ExternalElement =
        ExternalElement(
            probe = probe,
            width = width,
            height = height,
            nodeId = nodeId,
            modifier = Modifier.Empty.weight(weight, fill),
        )

    private fun ColumnScope.weightedElement(
        probe: ExternalProbe,
        nodeId: ExternalNodeId,
        weight: Float,
        width: Int,
        height: Int,
        fill: Boolean = true,
    ): ExternalElement =
        ExternalElement(
            probe = probe,
            width = width,
            height = height,
            nodeId = nodeId,
            modifier = Modifier.Empty.weight(weight, fill),
        )

    private fun paintBounds(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }

    private fun assertMeasuredAndPlaced(
        probe: ExternalProbe,
        vararg nodeIds: ExternalNodeId,
    ) {
        nodeIds.forEach { nodeId ->
            val node = requireNotNull(probe.componentNodes[nodeId])
            assertEquals(1, node.measures)
            assertEquals(1, node.layouts)
        }
    }

    private class Fixture(
        val probe: ExternalProbe,
    ) {
        val tree: UiTree = UiTree()
    }
}
