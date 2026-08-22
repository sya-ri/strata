@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
}
