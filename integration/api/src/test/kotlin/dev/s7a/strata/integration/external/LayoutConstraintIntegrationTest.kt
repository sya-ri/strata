@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies empty-container minima and box child constraint and placement contracts.
 */
internal class LayoutConstraintIntegrationTest {
    @Test
    fun emptyContainersAndSpacerHonorNonZeroMinimums() {
        val constraints = Constraints(minWidth = 7, maxWidth = 20, minHeight = 9, maxHeight = 20)

        assertEquals(IntSize(7, 9), measureRoot(constraints) { Row { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { Column { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { FlowRow { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { Stack { } })
        assertEquals(IntSize(7, 9), measureRoot(constraints) { Spacer() })
    }

    @Test
    fun boxLoosensBothChildMinimumsAndMeasuresAndPlacesEachChildOnce() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Stack {
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
            evaluateComponentTree {
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
            evaluateComponentTree {
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

    @Test
    fun flowRowMeasuresEachChildOnceWithTheFullParentMaximaBeforeWrapping() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                FlowRow(horizontalSpacing = 1, verticalSpacing = 1) {
                    element(ExternalElement(probe = probe, width = 6, height = 2, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = probe, width = 4, height = 3, nodeId = ExternalNodeId.Modifier))
                }
            },
        )

        assertEquals(IntSize(8, 12), tree.measure(Constraints.fixed(width = 8, height = 12)))
        assertEquals(
            List(2) { Constraints(maxWidth = 8, maxHeight = 12) },
            probe.componentMeasureConstraints,
        )
        assertEquals(listOf(ExternalNodeId.Child, ExternalNodeId.Modifier), probe.componentMeasureOrder)
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 6, 2), IntRect(0, 3, 4, 6)),
            tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds },
        )
        assertMeasuredAndPlaced(probe, ExternalNodeId.Child)
        assertMeasuredAndPlaced(probe, ExternalNodeId.Modifier)
        tree.close()
    }

    @Test
    fun flowRowPropagatesFixedChildConstraintViolationsInsteadOfShrinkingTheChild() {
        listOf(IntSize(9, 2), IntSize(2, 9)).forEach { size ->
            val tree = UiTree()
            tree.update(
                evaluateComponentTree {
                    FlowRow {
                        element(FixedSizeElement(size))
                    }
                },
            )

            assertThrows<IllegalStateException> { tree.measure(Constraints(maxWidth = 8, maxHeight = 8)) }
            assertEquals(TreeState.Poisoned, tree.state)
            tree.close()
        }
    }

    private fun measureRoot(
        constraints: Constraints,
        content: UiScope.() -> Unit,
    ): IntSize {
        val tree = UiTree()
        tree.update(evaluateComponentTree(content))
        val size = tree.measure(constraints)
        tree.close()
        return size
    }

    private fun boxTree(
        children: List<ExternalElement>,
    ): UiTree {
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Stack {
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

    private class FixedSizeElement(
        val size: IntSize,
    ) : Element(identity = ElementIdentity.Positional, type = TYPE) {
        companion object {
            val TYPE: ElementType<FixedSizeElement, FixedSizeNode> =
                ElementType(
                    elementClass = FixedSizeElement::class,
                    nodeClass = FixedSizeNode::class,
                    validateLocal = {},
                    createNode = { element -> FixedSizeNode(element.size) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class FixedSizeNode(
        private val size: IntSize,
    ) : Node(),
        MeasureNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = size
    }
}
