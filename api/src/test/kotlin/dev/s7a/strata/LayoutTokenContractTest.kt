package dev.s7a.strata

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.BoxAlignmentParentData
import dev.s7a.strata.layout.BoxElement
import dev.s7a.strata.layout.ColumnAlignmentParentData
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.LinearElement
import dev.s7a.strata.layout.LinearOrientation
import dev.s7a.strata.layout.RowAlignmentParentData
import dev.s7a.strata.layout.SpacerElement
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
        val box = BoxElement(Alignment.TopStart, null, emptyList(), Modifier.Empty)
        val spacer = SpacerElement(null, Modifier.Empty)

        assertSame(row.type, column.type)
        assertSame(box.type, BoxElement(Alignment.Center, null, emptyList(), Modifier.Empty).type)
        assertSame(spacer.type, SpacerElement(null, Modifier.Empty).type)
        assertNotSame(row.type, box.type)
        assertNotSame(row.type, spacer.type)
        assertNotSame(box.type, spacer.type)
    }

    @Test
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

        val box = BoxElement(Alignment.TopStart, null, emptyList(), Modifier.Empty)
        assertEquals(
            DirtyMask.None,
            BoxElement.TYPE.updateErased(box, box, BoxElement.TYPE.createErased(box)),
        )
        assertEquals(
            DirtyMask.of(DirtyPhase.Layout),
            BoxElement.TYPE.updateErased(
                box,
                BoxElement(Alignment.BottomEnd, null, emptyList(), Modifier.Empty),
                BoxElement.TYPE.createErased(box),
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
        assertBoxAlignmentContract()
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

    private fun assertBoxAlignmentContract() {
        val first = BoxAlignmentParentData.Element(BoxAlignmentParentData.Data(Alignment.TopStart))
        val equal = BoxAlignmentParentData.Element(BoxAlignmentParentData.Data(Alignment.TopStart))
        val changed = BoxAlignmentParentData.Element(BoxAlignmentParentData.Data(Alignment.BottomEnd))
        assertSame(first.type, equal.type)
        assertSame(BoxAlignmentParentData.TYPE, first.type)
        val equalNode = first.type.createErased(first)
        assertSame(BoxAlignmentParentData.KEY, (equalNode as BoxAlignmentParentData.Node).parentDataKey)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, equalNode))
        assertEquals(
            DirtyMask.of(DirtyPhase.Measure),
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
}
