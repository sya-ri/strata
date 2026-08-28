@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.external

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
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

    @Test
    fun flowRowReflowRetainsKeyedChildrenFocusAndUpdatedInputGeometry() {
        val probe = ExternalProbe()
        val transitions = ArrayList<FocusEvent>()
        var focusedKeys = 0
        val secondModifier =
            Modifier.Empty
                .onKeyPress {
                    focusedKeys += 1
                    InputResult.Consumed
                }.onFocusChanged(transitions::add)
        val tree = UiTree()
        tree.update(flowRowDescription(probe, secondModifier))
        assertEquals(IntSize(9, 4), tree.measure(Constraints(maxWidth = 9, maxHeight = 20)))
        tree.layout()
        val firstNode = requireNotNull(probe.componentNodes[ExternalNodeId.Child])
        val secondNode = requireNotNull(probe.componentNodes[ExternalNodeId.Modifier])
        val wideBounds = listOf(IntRect(0, 0, 4, 3), IntRect(5, 0, 9, 4))
        assertEquals(wideBounds, paintBounds(tree))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset(6, 1), PointerButton.Primary)))
        assertEquals(listOf(FocusEvent.Gained), transitions)

        tree.update(flowRowDescription(probe, secondModifier))
        assertEquals(IntSize(4, 8), tree.measure(Constraints(maxWidth = 4, maxHeight = 20)))
        tree.layout()
        assertEquals(listOf(IntRect(0, 0, 4, 3), IntRect(0, 4, 4, 8)), paintBounds(tree))
        assertSame(firstNode, probe.componentNodes[ExternalNodeId.Child])
        assertSame(secondNode, probe.componentNodes[ExternalNodeId.Modifier])
        assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 5), PointerButton.Primary)))

        tree.update(flowRowDescription(probe, secondModifier))
        assertEquals(IntSize(9, 4), tree.measure(Constraints(maxWidth = 9, maxHeight = 20)))
        tree.layout()
        assertEquals(wideBounds, paintBounds(tree))
        assertSame(firstNode, probe.componentNodes[ExternalNodeId.Child])
        assertSame(secondNode, probe.componentNodes[ExternalNodeId.Modifier])
        assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(IntOffset(6, 1), PointerButton.Primary)))
        assertEquals(2, focusedKeys)
        assertEquals(0, firstNode.presses)
        assertEquals(3, secondNode.presses)
        assertEquals(listOf(FocusEvent.Gained), transitions)
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Child),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier),
            ),
            probe.lifecycle,
        )
        tree.close()
    }

    private fun flowRowDescription(
        probe: ExternalProbe,
        secondModifier: Modifier,
    ): Element =
        evaluateComponentTree {
            FlowRow(key = ElementKey("flow"), horizontalSpacing = 1, verticalSpacing = 1) {
                element(
                    ExternalElement(
                        probe = probe,
                        key = ElementKey("first"),
                        width = 4,
                        height = 3,
                        nodeId = ExternalNodeId.Child,
                    ),
                )
                element(
                    ExternalElement(
                        probe = probe,
                        key = ElementKey("second"),
                        width = 4,
                        height = 4,
                        nodeId = ExternalNodeId.Modifier,
                        modifier = secondModifier,
                    ),
                )
            }
        }

    private fun buildRoot(children: List<BoxSpec>): Element =
        evaluateComponentTree {
            Row {
                children.forEach { child ->
                    Stack(key = child.key) {
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
