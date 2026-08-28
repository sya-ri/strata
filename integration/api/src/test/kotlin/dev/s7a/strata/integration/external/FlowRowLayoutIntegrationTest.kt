@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies width-driven wrapping and intrinsic line geometry through the external component boundary.
 */
internal class FlowRowLayoutIntegrationTest {
    @Test
    fun exactFitStaysOnOneLineAndOneLessPixelWrapsWithoutTrailingGaps() {
        val tree = wrappedTree()

        assertEquals(IntSize(8, 9), tree.measure(Constraints(maxWidth = 8)))
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(4, 0, 8, 4), IntRect(0, 6, 5, 9)),
            paintBounds(tree),
        )

        assertEquals(IntSize(5, 13), tree.measure(Constraints(maxWidth = 7)))
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(0, 4, 4, 8), IntRect(0, 10, 5, 13)),
            paintBounds(tree),
        )
        tree.close()
    }

    @Test
    fun unboundedWidthKeepsAllChildrenOnOneLine() {
        val tree = wrappedTree()

        assertEquals(IntSize(14, 4), tree.measure(Constraints()))
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(4, 0, 8, 4), IntRect(9, 0, 14, 3)),
            paintBounds(tree),
        )
        tree.close()
    }

    @Test
    fun naturalWidthMinimumWidthAndFillWidthAllArrangeAgainstTheResolvedContainer() {
        val tree = wrappedTree(arrangement = Arrangement.End)

        assertEquals(IntSize(8, 9), tree.measure(Constraints(maxWidth = 9)))
        tree.layout()
        assertEquals(
            listOf(IntRect(0, 0, 3, 2), IntRect(4, 0, 8, 4), IntRect(3, 6, 8, 9)),
            paintBounds(tree),
        )

        assertEquals(IntSize(9, 12), tree.measure(Constraints(minWidth = 9, maxWidth = 9, minHeight = 12)))
        tree.layout()
        assertEquals(
            listOf(IntRect(1, 0, 4, 2), IntRect(5, 0, 9, 4), IntRect(4, 6, 9, 9)),
            paintBounds(tree),
        )
        tree.close()

        val filled = wrappedTree(modifier = Modifier.Empty.fillMaxWidth(), arrangement = Arrangement.End)
        assertEquals(IntSize(9, 9), filled.measure(Constraints(maxWidth = 9)))
        filled.layout()
        assertEquals(
            listOf(IntRect(1, 0, 4, 2), IntRect(5, 0, 9, 4), IntRect(4, 6, 9, 9)),
            paintBounds(filled),
        )
        filled.close()
    }

    @Test
    fun zeroWidthChildrenStillParticipateInLinesAndSpacing() {
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                FlowRow(verticalSpacing = 2) {
                    element(ExternalElement(width = 0, height = 2))
                    element(ExternalElement(width = 0, height = 3))
                }
            },
        )
        assertEquals(IntSize(0, 3), tree.measure(Constraints(maxWidth = 0)))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 0, 2), IntRect(0, 0, 0, 3)), paintBounds(tree))

        tree.update(
            evaluateComponentTree {
                FlowRow(horizontalSpacing = 1, verticalSpacing = 2) {
                    element(ExternalElement(width = 0, height = 2))
                    element(ExternalElement(width = 0, height = 3))
                }
            },
        )
        assertEquals(IntSize(0, 7), tree.measure(Constraints(maxWidth = 0)))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 0, 2), IntRect(0, 4, 0, 7)), paintBounds(tree))
        tree.close()
    }

    @Test
    fun emptyAndSingleChildLayoutsDoNotAddUnusedSpacing() {
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                FlowRow(horizontalSpacing = Int.MAX_VALUE, verticalSpacing = Int.MAX_VALUE) { }
            },
        )
        assertEquals(IntSize.Zero, tree.measure(Constraints()))
        tree.layout()
        assertEquals(emptyList<IntRect>(), paintBounds(tree))

        tree.update(
            evaluateComponentTree {
                FlowRow(horizontalSpacing = Int.MAX_VALUE, verticalSpacing = Int.MAX_VALUE) {
                    element(ExternalElement(width = 2, height = 3))
                }
            },
        )
        assertEquals(IntSize(2, 3), tree.measure(Constraints(maxWidth = 10)))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 2, 3)), paintBounds(tree))
        tree.close()
    }

    @Test
    fun largeHorizontalSpacingWrapsWithoutOverflowingAnUnusedLineCandidate() {
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                FlowRow(horizontalSpacing = Int.MAX_VALUE, verticalSpacing = 1) {
                    element(ExternalElement(width = 1, height = 2))
                    element(ExternalElement(width = 1, height = 3))
                }
            },
        )

        assertEquals(IntSize(1, 6), tree.measure(Constraints(maxWidth = 3)))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 1, 2), IntRect(0, 3, 1, 6)), paintBounds(tree))
        tree.close()
    }

    @Test
    fun containerPaddingReducesWrapWidthAndTranslatesEveryLine() {
        val tree = wrappedTree(modifier = Modifier.Empty.padding(horizontal = 2, vertical = 3))

        assertEquals(IntSize(12, 15), tree.measure(Constraints(maxWidth = 12)))
        tree.layout()
        assertEquals(
            listOf(IntRect(2, 3, 5, 5), IntRect(6, 3, 10, 7), IntRect(2, 9, 7, 12)),
            paintBounds(tree),
        )
        tree.close()
    }

    @Test
    fun keyedChildSizeChangesReflowAndUpdateRowHeightsUnderEqualParentConstraints() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints(maxWidth = 8, maxHeight = 20)

        fun update(firstSize: IntSize) {
            tree.update(
                evaluateComponentTree {
                    FlowRow(key = ElementKey("flow"), horizontalSpacing = 1, verticalSpacing = 2) {
                        element(
                            ExternalElement(
                                probe = probe,
                                key = ElementKey("first"),
                                width = firstSize.width,
                                height = firstSize.height,
                                nodeId = ExternalNodeId.Child,
                            ),
                        )
                        element(
                            ExternalElement(
                                probe = probe,
                                key = ElementKey("second"),
                                width = 4,
                                height = 3,
                                nodeId = ExternalNodeId.Modifier,
                            ),
                        )
                    }
                },
            )
        }

        update(IntSize(3, 2))
        assertEquals(IntSize(8, 3), tree.measure(constraints))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 3, 2), IntRect(4, 0, 8, 3)), paintBounds(tree))
        val firstNode = requireNotNull(probe.componentNodes[ExternalNodeId.Child])
        val secondNode = requireNotNull(probe.componentNodes[ExternalNodeId.Modifier])

        update(IntSize(6, 5))
        assertEquals(IntSize(6, 10), tree.measure(constraints))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 6, 5), IntRect(0, 7, 4, 10)), paintBounds(tree))
        assertSame(firstNode, probe.componentNodes[ExternalNodeId.Child])
        assertSame(secondNode, probe.componentNodes[ExternalNodeId.Modifier])
        assertEquals(2, firstNode.measures)
        assertEquals(1, secondNode.measures)
        tree.close()
    }

    private fun wrappedTree(
        modifier: Modifier = Modifier.Empty,
        arrangement: Arrangement = Arrangement.Start,
    ): UiTree =
        UiTree().also { tree ->
            tree.update(
                evaluateComponentTree {
                    FlowRow(
                        modifier = modifier,
                        horizontalSpacing = 1,
                        verticalSpacing = 2,
                        horizontalArrangement = arrangement,
                    ) {
                        element(ExternalElement(width = 3, height = 2))
                        element(ExternalElement(width = 4, height = 4))
                        element(ExternalElement(width = 5, height = 3))
                    }
                },
            )
        }

    private fun paintBounds(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }
}
