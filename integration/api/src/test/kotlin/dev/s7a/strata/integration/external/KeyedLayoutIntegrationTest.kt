package dev.s7a.strata.integration.external

import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies keyed built-in sibling reordering and recoverable duplicate-key validation.
 */
internal class KeyedLayoutIntegrationTest {
    @Test
    fun keyedBuiltInSiblingsReorderPaintAndRetainTheirExternalNodes() {
        val first = BoxSpec(ElementKey("first"), ExternalProbe(), 3, 3)
        val second = BoxSpec(ElementKey("second"), ExternalProbe(), 4, 4)
        val tree = UiTree()
        tree.update(buildRoot(listOf(first, second)))
        tree.measure(Constraints.fixed(width = 12, height = 5))
        tree.layout()
        val firstNode = requireNotNull(first.probe.componentNodes[ExternalNodeId.Root])
        val secondNode = requireNotNull(second.probe.componentNodes[ExternalNodeId.Root])
        assertEquals(listOf(IntRect(0, 0, 3, 3), IntRect(3, 0, 7, 4)), paintBounds(tree))

        tree.update(buildRoot(listOf(second, first)))
        tree.measure(Constraints.fixed(width = 12, height = 5))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 4, 4), IntRect(4, 0, 7, 3)), paintBounds(tree))
        assertSame(firstNode, first.probe.componentNodes[ExternalNodeId.Root])
        assertSame(secondNode, second.probe.componentNodes[ExternalNodeId.Root])
        assertTrue(first.probe.lifecycle.none { event -> event is ExternalLifecycleEvent.Detach })
        assertTrue(first.probe.lifecycle.none { event -> event is ExternalLifecycleEvent.Dispose })
        assertTrue(second.probe.lifecycle.none { event -> event is ExternalLifecycleEvent.Detach })
        assertTrue(second.probe.lifecycle.none { event -> event is ExternalLifecycleEvent.Dispose })
        tree.close()
    }

    @Test
    fun duplicateBuiltInSiblingKeysFailBeforeMutationAndAllowCorrectedRetry() {
        val first = BoxSpec(ElementKey("first"), ExternalProbe(), 3, 3)
        val second = BoxSpec(ElementKey("second"), ExternalProbe(), 4, 4)
        val tree = UiTree()
        tree.update(buildRoot(listOf(first, second)))
        tree.measure(Constraints.fixed(width = 12, height = 5))
        tree.layout()
        val oldPaint = paintBounds(tree)
        val firstNode = requireNotNull(first.probe.componentNodes[ExternalNodeId.Root])
        val secondNode = requireNotNull(second.probe.componentNodes[ExternalNodeId.Root])
        val firstCounts = first.probe.nodeCounts()
        val secondCounts = second.probe.nodeCounts()
        val firstLifecycle = first.probe.lifecycle.toList()
        val secondLifecycle = second.probe.lifecycle.toList()

        val duplicate = second.copy(key = first.key)
        val failure =
            assertThrows<IllegalArgumentException> {
                tree.update(buildRoot(listOf(first, duplicate)))
            }

        assertTrue(failure.message.orEmpty().contains("Duplicate direct-sibling key"))
        assertEquals(TreeState.Active, tree.state)
        assertEquals(oldPaint, paintBoundsAfterCleanTree(tree))
        assertEquals(firstCounts, first.probe.nodeCounts())
        assertEquals(secondCounts, second.probe.nodeCounts())
        assertEquals(firstLifecycle, first.probe.lifecycle)
        assertEquals(secondLifecycle, second.probe.lifecycle)
        assertSame(firstNode, first.probe.componentNodes[ExternalNodeId.Root])
        assertSame(secondNode, second.probe.componentNodes[ExternalNodeId.Root])

        tree.update(buildRoot(listOf(first, second)))
        tree.measure(Constraints.fixed(width = 12, height = 5))
        tree.layout()
        assertEquals(oldPaint, paintBounds(tree))
        assertEquals(firstCounts, first.probe.nodeCounts())
        assertEquals(secondCounts, second.probe.nodeCounts())
        assertSame(firstNode, first.probe.componentNodes[ExternalNodeId.Root])
        assertSame(secondNode, second.probe.componentNodes[ExternalNodeId.Root])
        assertEquals(TreeState.Active, tree.state)
        tree.close()
    }

    private fun buildRoot(children: List<BoxSpec>): Element =
        buildUi {
            Row {
                children.forEach { child ->
                    Box(key = child.key) {
                        element(
                            ExternalElement(
                                probe = child.probe,
                                key = ElementKey("child"),
                                width = child.width,
                                height = child.height,
                                nodeId = ExternalNodeId.Root,
                            ),
                        )
                    }
                }
            }
        }

    private fun paintBoundsAfterCleanTree(tree: UiTree): List<IntRect> = tree.paint().map { command -> (command as DrawCommand.FillRectangle).bounds }

    private fun paintBounds(tree: UiTree): List<IntRect> = paintBoundsAfterCleanTree(tree)

    private fun ExternalProbe.nodeCounts(): NodeCounts {
        val node = requireNotNull(componentNodes[ExternalNodeId.Root])
        return NodeCounts(node.measures, node.layouts, node.paints)
    }

    private data class NodeCounts(
        val measures: Int,
        val layouts: Int,
        val paints: Int,
    )

    private data class BoxSpec(
        val key: ElementKey<String>,
        val probe: ExternalProbe,
        val width: Int,
        val height: Int,
    )
}
