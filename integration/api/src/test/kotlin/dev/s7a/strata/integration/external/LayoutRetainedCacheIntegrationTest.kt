@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies clean retained caches and narrow invalidation for built-in layout updates.
 */
internal class LayoutRetainedCacheIntegrationTest {
    @Test
    fun equalUpdateKeepsChildMeasureLayoutAndPaintCachesClean() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(rowDescription(probe))
        val constraints = Constraints.fixed(width = 12, height = 5)
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        val before = counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        val firstNode = requireNotNull(probe.componentNodes[ExternalNodeId.Child])
        val secondNode = requireNotNull(probe.componentNodes[ExternalNodeId.Modifier])

        tree.update(rowDescription(probe))
        tree.measure(constraints)
        tree.layout()
        tree.paint()

        assertEquals(before, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier))
        assertSame(firstNode, probe.componentNodes[ExternalNodeId.Child])
        assertSame(secondNode, probe.componentNodes[ExternalNodeId.Modifier])
        tree.close()
    }

    @Test
    fun arrangementUpdateRelocatesCachedChildrenWithoutChildMeasureOrPaint() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints.fixed(width = 12, height = 5)
        tree.update(rowDescription(probe, arrangement = Arrangement.Start))
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        val before = counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier)

        tree.update(rowDescription(probe, arrangement = Arrangement.End))
        tree.measure(constraints)
        tree.layout()

        assertEquals(listOf(IntRect(5, 0, 8, 2), IntRect(8, 0, 12, 3)), paintBounds(tree))
        tree.paint()
        assertEquals(before, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier))
        tree.close()
    }

    @Test
    fun boxAlignmentUpdateRelocatesCachedChildWithoutChildMeasureOrPaint() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints.fixed(width = 10, height = 10)
        tree.update(boxDescription(probe, Alignment.TopStart))
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        val before = counts(probe, ExternalNodeId.Child)

        tree.update(boxDescription(probe, Alignment.BottomEnd))
        tree.measure(constraints)
        tree.layout()

        assertEquals(listOf(IntRect(7, 7, 10, 10)), paintBounds(tree))
        tree.paint()
        assertEquals(before, counts(probe, ExternalNodeId.Child))
        tree.close()
    }

    @Test
    fun spacingUpdateChangesGeometryWithoutRemeasuringEqualConstraintChildren() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints.fixed(width = 12, height = 5)
        tree.update(rowDescription(probe, spacing = 0))
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        val before = counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier)

        tree.update(rowDescription(probe, spacing = 2))
        tree.measure(constraints)
        tree.layout()

        assertEquals(listOf(IntRect(0, 0, 3, 2), IntRect(5, 0, 9, 3)), paintBounds(tree))
        tree.paint()
        assertEquals(before, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier))
        tree.close()
    }

    @Test
    fun weightChangeRemeasuresAffectedChildrenAndEqualRetryIsClean() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints.fixed(width = 12, height = 5)
        tree.update(rowDescription(probe, firstWeight = 1f, secondWeight = 1f))
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        val before = counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        val firstNode = requireNotNull(probe.componentNodes[ExternalNodeId.Child])
        val secondNode = requireNotNull(probe.componentNodes[ExternalNodeId.Modifier])

        tree.update(rowDescription(probe, firstWeight = 2f, secondWeight = 1f))
        tree.measure(constraints)
        tree.layout()

        assertEquals(listOf(IntRect(0, 0, 8, 2), IntRect(8, 0, 12, 3)), paintBounds(tree))
        assertEquals(before.measures + 2, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier).measures)
        assertEquals(before.layouts + 2, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier).layouts)
        tree.paint()
        assertEquals(before.paints + 2, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier).paints)
        assertSame(firstNode, probe.componentNodes[ExternalNodeId.Child])
        assertSame(secondNode, probe.componentNodes[ExternalNodeId.Modifier])

        val changed = counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier)
        tree.update(rowDescription(probe, firstWeight = 2f, secondWeight = 1f))
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        assertEquals(changed, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier))
        tree.close()
    }

    @Test
    fun childAlignmentChangeRelocatesThroughMeasurementWithoutChildCallbackRerun() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints.fixed(width = 3, height = 10)
        tree.update(rowDescription(probe, childAlignment = VerticalAlignment.Top))
        tree.measure(constraints)
        tree.layout()
        tree.paint()
        val before = counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier)

        tree.update(rowDescription(probe, childAlignment = VerticalAlignment.Bottom))
        tree.measure(constraints)
        tree.layout()

        assertEquals(listOf(IntRect(0, 8, 3, 10), IntRect(3, 0, 6, 3)), paintBounds(tree))
        tree.paint()
        assertEquals(before, counts(probe, ExternalNodeId.Child, ExternalNodeId.Modifier))
        tree.close()
    }

    private fun rowDescription(
        probe: ExternalProbe,
        arrangement: Arrangement = Arrangement.Start,
        spacing: Int = 0,
        firstWeight: Float? = null,
        secondWeight: Float? = null,
        childAlignment: VerticalAlignment? = null,
    ): Element =
        evaluateComponentTree {
            Row(
                key = ElementKey("row"),
                spacing = spacing,
                horizontalArrangement = arrangement,
            ) {
                val firstModifier =
                    when {
                        firstWeight != null && childAlignment != null -> {
                            Modifier.Empty.weight(firstWeight).align(childAlignment)
                        }

                        firstWeight != null -> {
                            Modifier.Empty.weight(firstWeight)
                        }

                        childAlignment != null -> {
                            Modifier.Empty.align(childAlignment)
                        }

                        else -> {
                            Modifier.Empty
                        }
                    }
                val secondModifier =
                    if (secondWeight != null) {
                        Modifier.Empty.weight(secondWeight)
                    } else {
                        Modifier.Empty
                    }
                element(
                    ExternalElement(
                        probe = probe,
                        key = ElementKey("first"),
                        width = 3,
                        height = 2,
                        nodeId = ExternalNodeId.Child,
                        modifier = firstModifier,
                    ),
                )
                element(
                    ExternalElement(
                        probe = probe,
                        key = ElementKey("second"),
                        width = 4,
                        height = 3,
                        nodeId = ExternalNodeId.Modifier,
                        modifier = secondModifier,
                    ),
                )
            }
        }

    private fun boxDescription(
        probe: ExternalProbe,
        alignment: Alignment,
    ): Element =
        evaluateComponentTree {
            Stack(key = ElementKey("box"), contentAlignment = alignment) {
                element(
                    ExternalElement(
                        probe = probe,
                        key = ElementKey("child"),
                        width = 3,
                        height = 3,
                        nodeId = ExternalNodeId.Child,
                    ),
                )
            }
        }

    private fun counts(
        probe: ExternalProbe,
        vararg nodeIds: ExternalNodeId,
    ): Counts =
        Counts(
            measures = nodeIds.sumOf { nodeId -> node(probe, nodeId).measures },
            layouts = nodeIds.sumOf { nodeId -> node(probe, nodeId).layouts },
            paints = nodeIds.sumOf { nodeId -> node(probe, nodeId).paints },
        )

    private fun node(
        probe: ExternalProbe,
        nodeId: ExternalNodeId,
    ): ExternalNode = requireNotNull(probe.componentNodes[nodeId])

    private fun paintBounds(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }

    private data class Counts(
        val measures: Int,
        val layouts: Int,
        val paints: Int,
    )
}
