@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies fixed-column grid measurement, row-major placement, partial rows, and cell alignment through the external API boundary.
 */
internal class GridLayoutIntegrationTest {
    @Test
    fun naturalTracksUsePerColumnAndPerRowMaximaWithAPartialFinalRow() {
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Grid(
                    columns = 2,
                    horizontalSpacing = 1,
                    verticalSpacing = 2,
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    element(
                        ExternalElement(
                            width = 2,
                            height = 1,
                            modifier = Modifier.Empty.align(Alignment.TopStart),
                        ),
                    )
                    element(ExternalElement(width = 4, height = 3))
                    element(ExternalElement(width = 3, height = 2))
                }
            },
        )

        assertEquals(IntSize(8, 7), tree.measure(Constraints()))
        tree.layout()
        assertEquals(
            listOf(
                IntRect(0, 0, 2, 1),
                IntRect(4, 0, 8, 3),
                IntRect(0, 5, 3, 7),
            ),
            tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds },
        )
        tree.close()
    }

    @Test
    fun invalidColumnAndSpacingValuesFailBeforeAChildCallbackRuns() {
        var callbackCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree {
                Grid(columns = 0) {
                    callbackCalls += 1
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree {
                Grid(columns = 1, horizontalSpacing = -1) {
                    callbackCalls += 1
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree {
                Grid(columns = 1, verticalSpacing = -1) {
                    callbackCalls += 1
                }
            }
        }

        assertEquals(0, callbackCalls)
    }

    @Test
    fun unoccupiedTrailingTracksDoNotCreatePlaceholderExtent() {
        val emptyTree = UiTree()
        emptyTree.update(
            evaluateComponentTree {
                Grid(columns = 3, horizontalSpacing = 5, verticalSpacing = 7) { }
            },
        )
        assertEquals(IntSize.Zero, emptyTree.measure(Constraints()))
        emptyTree.close()

        val partialTree = UiTree()
        partialTree.update(
            evaluateComponentTree {
                Grid(columns = 3, horizontalSpacing = 5) {
                    element(ExternalElement(width = 2, height = 3))
                }
            },
        )
        assertEquals(IntSize(2, 3), partialTree.measure(Constraints()))
        partialTree.close()
    }

    @Test
    fun defaultAndParentDataOnlyAlignmentReadLiveDataWithoutRemeasuringLeaves() {
        val cells = cells()
        val tree = UiTree()
        tree.update(grid(cells, Alignment.TopStart, Alignment.TopStart))
        measureLayout(tree)
        val nodes = cells.map { checkNotNull(it.probe.componentNodes[ExternalNodeId.Root]) }
        tree.update(grid(cells, Alignment.BottomEnd, Alignment.TopStart))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 0, 2, 1), IntRect(4, 0, 8, 3), IntRect(0, 5, 3, 7)))
        tree.update(grid(cells, Alignment.BottomEnd, Alignment.Center))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 1, 2, 2), IntRect(4, 0, 8, 3), IntRect(0, 5, 3, 7)))
        assertEquals(listOf(1, 1, 1), nodes.map { it.measures })
        tree.close()
    }

    @Test
    fun keyedReorderRemovalReplacementAndEqualCountSizeSwapRefreshTracks() {
        val cells = cells()
        val tree = UiTree()
        tree.update(grid(cells))
        measureLayout(tree)
        val retained = cells.map { checkNotNull(it.probe.componentNodes[ExternalNodeId.Root]) }
        val reordered = listOf(cells[2], cells[0], cells[1])
        tree.update(grid(reordered))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 0, 3, 2), IntRect(5, 0, 7, 1), IntRect(0, 4, 4, 7)))
        cells.zip(retained).forEach { (cell, node) -> assertSame(node, cell.probe.componentNodes[ExternalNodeId.Root]) }
        tree.update(grid(reordered.take(2)))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 0, 3, 2), IntRect(4, 0, 6, 1)))
        val replacement = Cell(ElementKey(9), IntSize(5, 4), ExternalProbe())
        tree.update(grid(listOf(reordered[0], replacement)))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 0, 3, 2), IntRect(4, 0, 9, 4)))
        val swapped = listOf(reordered[0].copy(size = IntSize(5, 4)), replacement.copy(size = IntSize(3, 2)))
        tree.update(grid(swapped))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 0, 5, 4), IntRect(6, 0, 9, 2)))
        tree.close()
    }

    @Test
    fun constrainedOverflowRemainsPaintableAndReachableThroughCurrentSemanticsAndInput() {
        val tree = UiTree()
        tree.update(grid(cells()))
        assertEquals(IntSize(4, 4), tree.measure(Constraints.fixed(4, 4)))
        tree.layout()
        val bounds = listOf(IntRect(0, 0, 2, 1), IntRect(4, 0, 8, 3), IntRect(0, 5, 3, 7))
        assertGeometry(tree, bounds)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset(5, 1), PointerButton.Primary)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 6), PointerButton.Primary)))
        tree.close()
    }

    @Test
    fun columnSpacingAndConstraintChangesKeepExistingMeasureInvalidation() {
        val cells = cells()
        val tree = UiTree()
        tree.update(grid(cells))
        measureLayout(tree)
        tree.update(grid(cells, columns = 1, horizontalSpacing = 3, verticalSpacing = 1))
        measureLayout(tree)
        assertGeometry(tree, listOf(IntRect(0, 0, 2, 1), IntRect(0, 2, 4, 5), IntRect(0, 6, 3, 8)))
        assertEquals(listOf(1, 1, 1), cells.map { checkNotNull(it.probe.componentNodes[ExternalNodeId.Root]).measures })
        tree.measure(Constraints(maxWidth = 2, maxHeight = 2))
        tree.layout()
        assertGeometry(tree, listOf(IntRect(0, 0, 2, 1), IntRect(0, 2, 2, 4), IntRect(0, 5, 2, 7)))
        assertEquals(listOf(2, 2, 2), cells.map { checkNotNull(it.probe.componentNodes[ExternalNodeId.Root]).measures })
        tree.close()
    }

    private fun measureLayout(tree: UiTree) {
        tree.measure(Constraints())
        tree.layout()
    }

    private fun assertGeometry(tree: UiTree, expected: List<IntRect>) {
        assertEquals(expected, tree.paint().map { (it as DrawCommand.FillRectangle).bounds })
        assertEquals(expected, tree.semantics().map { it.bounds })
    }

    private fun cells(): List<Cell> = listOf(IntSize(2, 1), IntSize(4, 3), IntSize(3, 2)).mapIndexed { index, size ->
        Cell(ElementKey(index), size, ExternalProbe())
    }

    private fun grid(
        cells: List<Cell>,
        alignment: Alignment = Alignment.TopStart,
        firstAlignment: Alignment? = null,
        columns: Int = 2,
        horizontalSpacing: Int = 1,
        verticalSpacing: Int = 2,
    ): Element = evaluateComponentTree {
        Grid(columns, contentAlignment = alignment, horizontalSpacing = horizontalSpacing, verticalSpacing = verticalSpacing) {
            cells.forEachIndexed { index, cell ->
                val modifier = if (index == 0 && firstAlignment != null) Modifier.Empty.align(firstAlignment) else Modifier.Empty
                element(ExternalElement(probe = cell.probe, key = cell.key, width = cell.size.width, height = cell.size.height, modifier = modifier))
            }
        }
    }

    /**
     * Stable keyed cells with independent external callback probes.
     */
    private data class Cell(val key: ElementKey<Int>, val size: IntSize, val probe: ExternalProbe)
}
