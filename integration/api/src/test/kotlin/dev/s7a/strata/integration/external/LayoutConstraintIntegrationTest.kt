package dev.s7a.strata.integration.external

import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies empty-container minima and box child constraint and placement contracts.
 */
internal class LayoutConstraintIntegrationTest {
    @Test
    fun emptyContainersAndSpacerHonorNonZeroMinimums() {
        val constraints = Constraints(minWidth = 7, maxWidth = 20, minHeight = 9, maxHeight = 20)

        assertEquals(IntSize(7, 9), measureRoot(constraints) { Row { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { Column { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { Box { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { Spacer() })
    }

    @Test
    fun boxLoosensBothChildMinimumsAndMeasuresAndPlacesEachChildOnce() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            buildUi {
                Box {
                    element(
                        ExternalElement(
                            probe = probe,
                            width = 3,
                            height = 4,
                            nodeId = ExternalNodeId.Child,
                        ),
                    )
                    element(
                        ExternalElement(
                            probe = probe,
                            width = 5,
                            height = 2,
                            nodeId = ExternalNodeId.Modifier,
                        ),
                    )
                }
            },
        )

        assertEquals(IntSize(8, 9), tree.measure(Constraints.fixed(width = 8, height = 9)))
        assertEquals(
            listOf(
                Constraints(minWidth = 0, maxWidth = 8, minHeight = 0, maxHeight = 9),
                Constraints(minWidth = 0, maxWidth = 8, minHeight = 0, maxHeight = 9),
            ),
            probe.componentMeasureConstraints,
        )
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 3, 4), IntRect(0, 0, 5, 2)),
            tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds },
        )
        assertMeasuredAndPlaced(probe, ExternalNodeId.Child)
        assertMeasuredAndPlaced(probe, ExternalNodeId.Modifier)
        tree.close()
    }

    @Test
    fun boxReportsTheLargestNaturalChildExtentAndClampsOversizedChildren() {
        val naturalProbe = ExternalProbe()
        val naturalTree =
            boxTree(
                listOf(
                    ExternalElement(
                        probe = naturalProbe,
                        width = 3,
                        height = 4,
                        nodeId = ExternalNodeId.Child,
                    ),
                    ExternalElement(
                        probe = naturalProbe,
                        width = 5,
                        height = 2,
                        nodeId = ExternalNodeId.Modifier,
                    ),
                ),
            )
        assertEquals(IntSize(5, 4), naturalTree.measure(Constraints(maxWidth = 20, maxHeight = 20)))
        naturalTree.close()

        val boundedProbe = ExternalProbe()
        val boundedTree =
            boxTree(
                listOf(
                    ExternalElement(
                        probe = boundedProbe,
                        width = 30,
                        height = 40,
                    ),
                ),
            )
        assertEquals(IntSize(8, 9), boundedTree.measure(Constraints(maxWidth = 8, maxHeight = 9)))
        assertEquals(
            listOf(Constraints(minWidth = 0, maxWidth = 8, minHeight = 0, maxHeight = 9)),
            boundedProbe.componentMeasureConstraints,
        )
        boundedTree.close()
    }

    @Test
    fun linearContainersReportNaturalExtentsAndLoosenFixedChildMinimums() {
        val rowProbe = ExternalProbe()
        val rowTree = UiTree()
        rowTree.update(
            buildUi {
                Row(spacing = 2) {
                    element(ExternalElement(probe = rowProbe, width = 3, height = 4, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = rowProbe, width = 5, height = 2, nodeId = ExternalNodeId.Modifier))
                }
            },
        )
        assertEquals(IntSize(10, 4), rowTree.measure(Constraints(maxWidth = 20, maxHeight = 30)))
        assertEquals(
            List(2) { Constraints(minWidth = 0, maxWidth = 20, minHeight = 0, maxHeight = 30) },
            rowProbe.componentMeasureConstraints,
        )
        rowTree.close()

        val columnProbe = ExternalProbe()
        val columnTree = UiTree()
        columnTree.update(
            buildUi {
                Column(spacing = 2) {
                    element(ExternalElement(probe = columnProbe, width = 3, height = 4, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = columnProbe, width = 5, height = 2, nodeId = ExternalNodeId.Modifier))
                }
            },
        )
        assertEquals(IntSize(5, 8), columnTree.measure(Constraints(maxWidth = 20, maxHeight = 30)))
        assertEquals(
            List(2) { Constraints(minWidth = 0, maxWidth = 20, minHeight = 0, maxHeight = 30) },
            columnProbe.componentMeasureConstraints,
        )
        columnTree.close()
    }

    private fun measureRoot(
        constraints: Constraints,
        content: UiScope.() -> Unit,
    ): IntSize {
        val tree = UiTree()
        tree.update(buildUi(content))
        val size = tree.measure(constraints)
        tree.close()
        return size
    }

    private fun boxTree(
        children: List<ExternalElement>,
    ): UiTree {
        val tree = UiTree()
        tree.update(
            buildUi {
                Box {
                    children.forEach { child -> element(child) }
                }
            },
        )
        return tree
    }

    private fun assertMeasuredAndPlaced(
        probe: ExternalProbe,
        nodeId: ExternalNodeId,
    ) {
        val node = requireNotNull(probe.componentNodes[nodeId])
        assertEquals(1, node.measures)
        assertEquals(1, node.layouts)
    }
}
