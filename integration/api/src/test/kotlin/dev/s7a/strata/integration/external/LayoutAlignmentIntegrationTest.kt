package dev.s7a.strata.integration.external

import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies typed box, row, and column alignment policies and parent-data overrides.
 */
internal class LayoutAlignmentIntegrationTest {
    @Test
    fun boxAlignmentsUseBothOddCrossAxisDifferences() {
        val expected =
            mapOf(
                Alignment.TopStart to IntRect(0, 0, 3, 3),
                Alignment.TopCenter to IntRect(3, 0, 6, 3),
                Alignment.TopEnd to IntRect(7, 0, 10, 3),
                Alignment.CenterStart to IntRect(0, 3, 3, 6),
                Alignment.Center to IntRect(3, 3, 6, 6),
                Alignment.CenterEnd to IntRect(7, 3, 10, 6),
                Alignment.BottomStart to IntRect(0, 7, 3, 10),
                Alignment.BottomCenter to IntRect(3, 7, 6, 10),
                Alignment.BottomEnd to IntRect(7, 7, 10, 10),
            )

        Alignment.entries.forEach { alignment ->
            assertEquals(expected.getValue(alignment), boxBounds(alignment))
        }
    }

    @Test
    fun rowAndColumnDefaultsAndChildOverridesUseTreeCoordinates() {
        assertEquals(IntRect(0, 0, 3, 3), rowBounds(VerticalAlignment.Top))
        assertEquals(IntRect(0, 3, 3, 6), rowBounds(VerticalAlignment.Center))
        assertEquals(IntRect(0, 7, 3, 10), rowBounds(VerticalAlignment.Bottom))
        assertEquals(IntRect(0, 0, 3, 3), columnBounds(HorizontalAlignment.Start))
        assertEquals(IntRect(3, 0, 6, 3), columnBounds(HorizontalAlignment.Center))
        assertEquals(IntRect(7, 0, 10, 3), columnBounds(HorizontalAlignment.End))

        assertEquals(
            IntRect(0, 7, 3, 10),
            rowBounds(VerticalAlignment.Top, VerticalAlignment.Bottom),
        )
        assertEquals(
            IntRect(7, 0, 10, 3),
            columnBounds(HorizontalAlignment.Start, HorizontalAlignment.End),
        )
        assertEquals(
            IntRect(7, 7, 10, 10),
            boxBounds(Alignment.TopStart, Alignment.BottomEnd),
        )
    }

    @Test
    fun publicDefaultArgumentsPlaceChildrenAtTheTopStartEdges() {
        val rowTree = UiTree()
        rowTree.update(
            buildUi {
                Row {
                    element(ExternalElement(width = 3, height = 3))
                }
            },
        )
        rowTree.measure(Constraints.fixed(width = 3, height = 10))
        rowTree.layout()
        assertEquals(IntRect(0, 0, 3, 3), paintBounds(rowTree).single())
        rowTree.close()

        val columnTree = UiTree()
        columnTree.update(
            buildUi {
                Column {
                    element(ExternalElement(width = 3, height = 3))
                }
            },
        )
        columnTree.measure(Constraints.fixed(width = 10, height = 3))
        columnTree.layout()
        assertEquals(IntRect(0, 0, 3, 3), paintBounds(columnTree).single())
        columnTree.close()

        val boxTree = UiTree()
        boxTree.update(
            buildUi {
                Box {
                    element(ExternalElement(width = 3, height = 3))
                }
            },
        )
        boxTree.measure(Constraints.fixed(width = 10, height = 10))
        boxTree.layout()
        assertEquals(IntRect(0, 0, 3, 3), paintBounds(boxTree).single())
        boxTree.close()
    }

    @Test
    fun innermostRepeatedAlignmentProviderWinsInSourceOrder() {
        assertRepeatedRowAlignment()
        assertRepeatedColumnAlignment()
        assertRepeatedBoxAlignment()
    }

    private fun assertRepeatedRowAlignment() {
        val rowTree = UiTree()
        val rowProbe = ExternalProbe()
        rowTree.update(
            buildUi {
                Row {
                    element(
                        ExternalElement(
                            probe = rowProbe,
                            width = 3,
                            height = 3,
                            modifier = Modifier.Empty.align(VerticalAlignment.Top).align(VerticalAlignment.Bottom),
                        ),
                    )
                }
            },
        )
        rowTree.measure(Constraints.fixed(width = 3, height = 10))
        rowTree.layout()
        assertEquals(IntRect(0, 7, 3, 10), paintBounds(rowTree).single())
        rowTree.close()
    }

    private fun assertRepeatedColumnAlignment() {
        val columnTree = UiTree()
        val columnProbe = ExternalProbe()
        columnTree.update(
            buildUi {
                Column {
                    element(
                        ExternalElement(
                            probe = columnProbe,
                            width = 3,
                            height = 3,
                            modifier = Modifier.Empty.align(HorizontalAlignment.Start).align(HorizontalAlignment.End),
                        ),
                    )
                }
            },
        )
        columnTree.measure(Constraints.fixed(width = 10, height = 3))
        columnTree.layout()
        assertEquals(IntRect(7, 0, 10, 3), paintBounds(columnTree).single())
        columnTree.close()
    }

    private fun assertRepeatedBoxAlignment() {
        val boxTree = UiTree()
        val boxProbe = ExternalProbe()
        boxTree.update(
            buildUi {
                Box {
                    element(
                        ExternalElement(
                            probe = boxProbe,
                            width = 3,
                            height = 3,
                            modifier = Modifier.Empty.align(Alignment.TopStart).align(Alignment.BottomEnd),
                        ),
                    )
                }
            },
        )
        boxTree.measure(Constraints.fixed(width = 10, height = 10))
        boxTree.layout()
        assertEquals(IntRect(7, 7, 10, 10), paintBounds(boxTree).single())
        boxTree.close()
    }

    private fun boxBounds(
        contentAlignment: Alignment,
        childAlignment: Alignment? = null,
    ): IntRect {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            buildUi {
                Box(contentAlignment = contentAlignment) {
                    val modifier =
                        if (childAlignment == null) {
                            Modifier.Empty
                        } else {
                            Modifier.Empty.align(childAlignment)
                        }
                    element(ExternalElement(probe = probe, width = 3, height = 3, modifier = modifier))
                }
            },
        )
        tree.measure(Constraints.fixed(width = 10, height = 10))
        tree.layout()
        val bounds = paintBounds(tree).single()
        tree.close()
        return bounds
    }

    private fun rowBounds(
        alignment: VerticalAlignment,
        childAlignment: VerticalAlignment? = null,
    ): IntRect {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            buildUi {
                Row(verticalAlignment = alignment) {
                    val modifier =
                        if (childAlignment == null) {
                            Modifier.Empty
                        } else {
                            Modifier.Empty.align(childAlignment)
                        }
                    element(ExternalElement(probe = probe, width = 3, height = 3, modifier = modifier))
                }
            },
        )
        tree.measure(Constraints.fixed(width = 3, height = 10))
        tree.layout()
        val bounds = paintBounds(tree).single()
        tree.close()
        return bounds
    }

    private fun columnBounds(
        alignment: HorizontalAlignment,
        childAlignment: HorizontalAlignment? = null,
    ): IntRect {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            buildUi {
                Column(horizontalAlignment = alignment) {
                    val modifier =
                        if (childAlignment == null) {
                            Modifier.Empty
                        } else {
                            Modifier.Empty.align(childAlignment)
                        }
                    element(ExternalElement(probe = probe, width = 3, height = 3, modifier = modifier))
                }
            },
        )
        tree.measure(Constraints.fixed(width = 10, height = 3))
        tree.layout()
        val bounds = paintBounds(tree).single()
        tree.close()
        return bounds
    }

    private fun paintBounds(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }
}
