@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies built-in layout components with an external primitive child.
 */
internal class LayoutIntegrationTest {
    @Test
    fun rowArrangesExternalChildrenAndPreservesLogicalPaintOrder() {
        val tree = UiTree()
        val probe = ExternalProbe()
        val root =
            evaluateComponentTree {
                Row(
                    spacing = 2,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    element(ExternalElement(probe = probe, width = 3, height = 4, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = probe, width = 5, height = 2, nodeId = ExternalNodeId.Modifier))
                }
            }
        tree.update(root)
        tree.measure(Constraints.fixed(width = 20, height = 10))
        tree.layout()

        val commands = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }
        assertEquals(listOf(IntRect(0, 0, 3, 4), IntRect(15, 0, 20, 2)), commands)
        tree.close()
    }

    @Test
    fun rowModifierAppliesContainerPaddingAndBackgroundOnce() {
        val tree = UiTree()
        val probe = ExternalProbe()
        val background = ArgbColor(0xFF102030.toInt())
        tree.update(
            evaluateComponentTree {
                Row(
                    modifier =
                        Modifier.Empty
                            .background(background)
                            .padding(Insets(left = 2, top = 5, right = 3, bottom = 4)),
                    spacing = 1,
                ) {
                    element(ExternalElement(probe = probe, width = 3, height = 2, nodeId = ExternalNodeId.Child))
                    element(ExternalElement(probe = probe, width = 4, height = 3, nodeId = ExternalNodeId.Modifier))
                }
            },
        )

        assertEquals(IntSize(13, 12), tree.measure(Constraints(maxWidth = 50, maxHeight = 50)))
        tree.layout()
        assertEquals(
            listOf(
                IntRect(0, 0, 13, 12),
                IntRect(2, 5, 5, 7),
                IntRect(6, 5, 10, 8),
            ),
            tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds },
        )
        tree.close()
    }

    @Test
    fun weightedRowAllocatesRatioAndSpacerUsesConstrainedZero() {
        val weightedProbe = ExternalProbe()
        val weightedTree = UiTree()
        weightedTree.update(
            evaluateComponentTree {
                Row {
                    element(
                        ExternalElement(
                            probe = weightedProbe,
                            width = 1,
                            height = 2,
                            modifier = Modifier.Empty.weight(1f),
                        ),
                    )
                    element(
                        ExternalElement(
                            probe = weightedProbe,
                            width = 1,
                            height = 2,
                            modifier = Modifier.Empty.weight(2f),
                        ),
                    )
                }
            },
        )
        weightedTree.measure(Constraints.fixed(width = 20, height = 10))
        assertEquals(Constraints(minWidth = 6, maxWidth = 6, minHeight = 0, maxHeight = 10), weightedProbe.componentMeasureConstraints[0])
        assertEquals(Constraints(minWidth = 14, maxWidth = 14, minHeight = 0, maxHeight = 10), weightedProbe.componentMeasureConstraints[1])
        weightedTree.close()

        val spacerTree = UiTree()
        spacerTree.update(evaluateComponentTree { Spacer() })
        assertEquals(IntSize(7, 9), spacerTree.measure(Constraints(minWidth = 7, minHeight = 9)))
        spacerTree.close()
    }

    @Test
    fun weightedRowUsesExactFloatRatiosAtExtremeMagnitudes() {
        val largeFirstProbe = ExternalProbe()
        val largeFirstTree = UiTree()
        largeFirstTree.update(
            evaluateComponentTree {
                Row {
                    element(
                        ExternalElement(
                            probe = largeFirstProbe,
                            modifier = Modifier.Empty.weight(Float.MAX_VALUE),
                        ),
                    )
                    element(
                        ExternalElement(
                            probe = largeFirstProbe,
                            modifier = Modifier.Empty.weight(Float.MIN_VALUE),
                        ),
                    )
                }
            },
        )
        largeFirstTree.measure(Constraints.fixed(width = 100, height = 10))
        assertEquals(Constraints(minWidth = 99, maxWidth = 99, minHeight = 0, maxHeight = 10), largeFirstProbe.componentMeasureConstraints[0])
        assertEquals(Constraints(minWidth = 1, maxWidth = 1, minHeight = 0, maxHeight = 10), largeFirstProbe.componentMeasureConstraints[1])
        largeFirstTree.close()

        val smallFirstProbe = ExternalProbe()
        val smallFirstTree = UiTree()
        smallFirstTree.update(
            evaluateComponentTree {
                Row {
                    element(
                        ExternalElement(
                            probe = smallFirstProbe,
                            modifier = Modifier.Empty.weight(Float.MIN_VALUE),
                        ),
                    )
                    element(
                        ExternalElement(
                            probe = smallFirstProbe,
                            modifier = Modifier.Empty.weight(Float.MAX_VALUE),
                        ),
                    )
                }
            },
        )
        smallFirstTree.measure(Constraints.fixed(width = 100, height = 10))
        assertEquals(Constraints(minWidth = 0, maxWidth = 0, minHeight = 0, maxHeight = 10), smallFirstProbe.componentMeasureConstraints[0])
        assertEquals(Constraints(minWidth = 100, maxWidth = 100, minHeight = 0, maxHeight = 10), smallFirstProbe.componentMeasureConstraints[1])
        smallFirstTree.close()
    }

    @Test
    fun boxUsesTypedAlignmentOverride() {
        val tree = UiTree()
        val probe = ExternalProbe()
        tree.update(
            evaluateComponentTree {
                Stack(contentAlignment = Alignment.TopStart) {
                    element(
                        ExternalElement(
                            probe = probe,
                            width = 3,
                            height = 4,
                            modifier = Modifier.Empty.align(Alignment.BottomEnd),
                        ),
                    )
                }
            },
        )
        tree.measure(Constraints.fixed(width = 20, height = 10))
        tree.layout()
        assertEquals(listOf(IntRect(17, 6, 20, 10)), tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds })
        tree.close()
    }

    @Test
    fun sameKeyRowToColumnReusesDescendantsAndChangesGeometry() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(keyedRow(probe))
        assertEquals(IntSize(5, 3), tree.measure(Constraints(maxWidth = 20, maxHeight = 20)))

        tree.update(keyedColumn(probe))
        assertEquals(IntSize(3, 5), tree.measure(Constraints(maxWidth = 20, maxHeight = 20)))
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Child),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier),
            ),
            probe.lifecycle,
        )
        tree.close()
    }

    private fun keyedRow(probe: ExternalProbe) =
        evaluateComponentTree {
            Row(key = ElementKey("linear")) {
                keyedChildren(probe).forEach(::element)
            }
        }

    private fun keyedColumn(probe: ExternalProbe) =
        evaluateComponentTree {
            Column(key = ElementKey("linear")) {
                keyedChildren(probe).forEach(::element)
            }
        }

    private fun keyedChildren(probe: ExternalProbe): List<ExternalElement> =
        listOf(
            ExternalElement(
                probe = probe,
                key = ElementKey("first"),
                width = 3,
                height = 2,
                nodeId = ExternalNodeId.Child,
            ),
            ExternalElement(
                probe = probe,
                key = ElementKey("second"),
                width = 2,
                height = 3,
                nodeId = ExternalNodeId.Modifier,
            ),
        )
}
