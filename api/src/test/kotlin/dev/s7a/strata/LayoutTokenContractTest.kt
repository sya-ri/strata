package dev.s7a.strata

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.ColumnAlignmentParentData
import dev.s7a.strata.layout.GridAlignmentParentData
import dev.s7a.strata.layout.GridElement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LinearElement
import dev.s7a.strata.layout.LinearOrientation
import dev.s7a.strata.layout.RowAlignmentParentData
import dev.s7a.strata.layout.SpacerElement
import dev.s7a.strata.layout.StackAlignmentParentData
import dev.s7a.strata.layout.StackElement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.layout.WeightParentData
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies stable built-in element, modifier, and parent-data token contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class LayoutTokenContractTest {
    @Test
    fun layoutElementTypesAreStableAndLinearVariantsShareOneToken() {
        val row = linearRow()
        val column = linearColumn()
        val stack = StackElement(Alignment.TopStart, null, emptyList(), Modifier.Empty)
        val grid = grid()
        val spacer = SpacerElement(null, Modifier.Empty)

        assertSame(row.type, column.type)
        assertSame(stack.type, StackElement(Alignment.Center, null, emptyList(), Modifier.Empty).type)
        assertSame(grid.type, grid(alignment = Alignment.Center).type)
        assertSame(spacer.type, SpacerElement(null, Modifier.Empty).type)
        assertNotSame(row.type, stack.type)
        assertNotSame(row.type, spacer.type)
        assertNotSame(stack.type, spacer.type)
        assertNotSame(grid.type, row.type)
        assertNotSame(grid.type, stack.type)
        assertNotSame(grid.type, spacer.type)
    }

    @Test
    @Suppress("LongMethod")
    fun layoutElementUpdatesReportOnlyAffectedPhases() {
        val linearEqual = linearRow()
        assertEquals(
            DirtyMask.None,
            LinearElement.TYPE.updateErased(
                linearEqual,
                linearEqual,
                LinearElement.TYPE.createErased(linearEqual),
            ),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            LinearElement.TYPE.updateErased(
                linearEqual,
                linearRow(spacing = 1),
                LinearElement.TYPE.createErased(linearEqual),
            ),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Layout),
            LinearElement.TYPE.updateErased(
                linearRow(),
                linearRow(arrangement = Arrangement.End),
                LinearElement.TYPE.createErased(linearRow()),
            ),
        )

        val grid = grid()
        assertEquals(DirtyMask.None, GridElement.TYPE.updateErased(grid, grid, GridElement.TYPE.createErased(grid)))
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            GridElement.TYPE.updateErased(grid, grid(columns = 3), GridElement.TYPE.createErased(grid)),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Layout),
            GridElement.TYPE.updateErased(grid, grid(alignment = Alignment.BottomEnd), GridElement.TYPE.createErased(grid)),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Layout),
            LinearElement.TYPE.updateErased(
                linearRow(),
                linearRow(alignment = VerticalAlignment.Bottom),
                LinearElement.TYPE.createErased(linearRow()),
            ),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            LinearElement.TYPE.updateErased(
                linearRow(),
                linearColumn(),
                LinearElement.TYPE.createErased(linearRow()),
            ),
        )

        val stack = StackElement(Alignment.TopStart, null, emptyList(), Modifier.Empty)
        assertEquals(
            DirtyMask.None,
            StackElement.TYPE.updateErased(stack, stack, StackElement.TYPE.createErased(stack)),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Layout),
            StackElement.TYPE.updateErased(
                stack,
                StackElement(Alignment.BottomEnd, null, emptyList(), Modifier.Empty),
                StackElement.TYPE.createErased(stack),
            ),
        )

        val spacer = SpacerElement(null, Modifier.Empty)
        val spacerNode = SpacerElement.TYPE.createErased(spacer)
        assertEquals(DirtyMask.None, SpacerElement.TYPE.updateErased(spacer, spacer, spacerNode))
    }

    @Test
    fun parentDataFamiliesHaveStableKeysAndNarrowUpdateMasks() {
        assertWeightContract()
        assertRowAlignmentContract()
        assertColumnAlignmentContract()
        assertStackAlignmentContract()
        assertGridAlignmentContract()
    }

    @Test
    fun internalDescriptionsEnforceTheirConstructionAndTokenBoundaries() {
        assertThrows<IllegalArgumentException> { linearRow(spacing = -1) }
        listOf(
            0f,
            -1f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        ).forEach { weight ->
            val element = WeightParentData.Element(WeightParentData.Data(weight, true))
            assertThrows<IllegalArgumentException> {
                WeightParentData.TYPE.validateErased(element)
            }
        }
    }

    private fun assertWeightContract() {
        val first = WeightParentData.Element(WeightParentData.Data(1f, true))
        val equal = WeightParentData.Element(WeightParentData.Data(1f, true))
        val changedWeight = WeightParentData.Element(WeightParentData.Data(2f, true))
        val changedFill = WeightParentData.Element(WeightParentData.Data(1f, false))
        assertSame(first.type, equal.type)
        assertSame(WeightParentData.TYPE, first.type)
        val equalNode = first.type.createErased(first)
        assertSame(WeightParentData.KEY, (equalNode as WeightParentData.Node).parentDataKey)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, equalNode))
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            first.type.updateErased(first, changedWeight, first.type.createErased(first)),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            first.type.updateErased(first, changedFill, first.type.createErased(first)),
        )
    }

    private fun assertRowAlignmentContract() {
        val first = RowAlignmentParentData.Element(RowAlignmentParentData.Data(VerticalAlignment.Top))
        val equal = RowAlignmentParentData.Element(RowAlignmentParentData.Data(VerticalAlignment.Top))
        val changed = RowAlignmentParentData.Element(RowAlignmentParentData.Data(VerticalAlignment.Bottom))
        assertSame(first.type, equal.type)
        assertSame(RowAlignmentParentData.TYPE, first.type)
        val equalNode = first.type.createErased(first)
        assertSame(RowAlignmentParentData.KEY, (equalNode as RowAlignmentParentData.Node).parentDataKey)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, equalNode))
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            first.type.updateErased(first, changed, first.type.createErased(first)),
        )
    }

    private fun assertColumnAlignmentContract() {
        val first = ColumnAlignmentParentData.Element(ColumnAlignmentParentData.Data(HorizontalAlignment.Start))
        val equal = ColumnAlignmentParentData.Element(ColumnAlignmentParentData.Data(HorizontalAlignment.Start))
        val changed = ColumnAlignmentParentData.Element(ColumnAlignmentParentData.Data(HorizontalAlignment.End))
        assertSame(first.type, equal.type)
        assertSame(ColumnAlignmentParentData.TYPE, first.type)
        val equalNode = first.type.createErased(first)
        assertSame(ColumnAlignmentParentData.KEY, (equalNode as ColumnAlignmentParentData.Node).parentDataKey)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, equalNode))
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            first.type.updateErased(first, changed, first.type.createErased(first)),
        )
    }

    private fun assertStackAlignmentContract() {
        val first = StackAlignmentParentData.Element(StackAlignmentParentData.Data(Alignment.TopStart))
        val equal = StackAlignmentParentData.Element(StackAlignmentParentData.Data(Alignment.TopStart))
        val changed = StackAlignmentParentData.Element(StackAlignmentParentData.Data(Alignment.BottomEnd))
        assertSame(first.type, equal.type)
        assertSame(StackAlignmentParentData.TYPE, first.type)
        val equalNode = first.type.createErased(first)
        assertSame(StackAlignmentParentData.KEY, (equalNode as StackAlignmentParentData.Node).parentDataKey)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, equalNode))
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
            first.type.updateErased(first, changed, first.type.createErased(first)),
        )
    }

    private fun assertGridAlignmentContract() {
        val first = GridAlignmentParentData.Element(GridAlignmentParentData.Data(Alignment.TopStart))
        val equal = GridAlignmentParentData.Element(GridAlignmentParentData.Data(Alignment.TopStart))
        val changed = GridAlignmentParentData.Element(GridAlignmentParentData.Data(Alignment.BottomEnd))
        assertSame(first.type, equal.type)
        assertSame(GridAlignmentParentData.TYPE, first.type)
        val equalNode = first.type.createErased(first)
        assertSame(GridAlignmentParentData.KEY, (equalNode as GridAlignmentParentData.Node).parentDataKey)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, equalNode))
        assertEquals(
            DirtyMask.of(DirtyPhase.Layout),
            first.type.updateErased(first, changed, first.type.createErased(first)),
        )
    }

    private fun linearRow(
        spacing: Int = 0,
        arrangement: Arrangement = Arrangement.Start,
        alignment: VerticalAlignment = VerticalAlignment.Top,
    ): LinearElement =
        LinearElement(
            orientation = LinearOrientation.Row(alignment),
            spacing = spacing,
            arrangement = arrangement,
            key = null,
            children = emptyList(),
            modifier = Modifier.Empty,
        )

    private fun linearColumn(): LinearElement =
        LinearElement(
            orientation = LinearOrientation.Column(HorizontalAlignment.Start),
            spacing = 0,
            arrangement = Arrangement.Start,
            key = null,
            children = emptyList(),
            modifier = Modifier.Empty,
        )

    private fun grid(
        columns: Int = 2,
        alignment: Alignment = Alignment.TopStart,
    ): GridElement =
        GridElement(
            columns = columns,
            horizontalSpacing = 0,
            verticalSpacing = 0,
            contentAlignment = alignment,
            key = null,
            children = emptyList(),
            modifier = Modifier.Empty,
        )
}
