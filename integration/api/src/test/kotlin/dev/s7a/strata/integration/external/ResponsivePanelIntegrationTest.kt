package dev.s7a.strata.integration.external

import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.integration.consumer.ApiOnlyResponsivePanel
import dev.s7a.strata.integration.consumer.ApiOnlyResponsivePanel.ResponsivePanel
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Exercises the compiled consumer composition through retained measurement, clipping, semantics, and input.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ResponsivePanelIntegrationTest {
    @Test
    fun completeScreenFactoryFitsSmallAndEmptyViewports() {
        val definition = ApiOnlyResponsivePanel.createDefinition()
        val tree = UiTree()
        tree.update(evaluateComponentTree(definition.transfer().content))
        try {
            listOf(IntSize(640, 360), IntSize(1, 1), IntSize.Zero, IntSize(640, 360)).forEach { viewport ->
                assertEquals(viewport, tree.measure(Constraints.fixed(viewport.width, viewport.height)))
                tree.layout()
                val bounds =
                    tree
                        .paint()
                        .filterIsInstance<DrawCommand.PushClip>()
                        .single()
                        .bounds
                assertEquals(
                    bounds,
                    tree
                        .paint()
                        .filterIsInstance<DrawCommand.FillRectangle>()
                        .single()
                        .bounds,
                )
                assertEquals(bounds, tree.semantics().single().bounds)
            }
        } finally {
            tree.close()
            definition.close()
        }
    }

    @Test
    fun resizeAcrossBothInclusiveBreakpointsUpdatesGeometryWithoutRebuildingContent() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(panel(probe))
        val component = probe.componentNodes.getValue(ExternalNodeId.Root)
        val cases =
            listOf(
                IntSize(640, 360) to IntRect(160, 90, 480, 270),
                IntSize(479, 270) to IntRect(0, 0, 479, 270),
                IntSize(480, 270) to IntRect(120, 67, 360, 202),
                IntSize(480, 269) to IntRect(0, 0, 480, 269),
                IntSize(481, 271) to IntRect(120, 68, 360, 203),
                IntSize(1, 1) to IntRect(0, 0, 1, 1),
                IntSize(640, 360) to IntRect(160, 90, 480, 270),
            )
        try {
            cases.forEach { (viewport, bounds) ->
                assertEquals(viewport, tree.measure(Constraints.fixed(viewport.width, viewport.height)))
                tree.layout()
                assertEquals(Constraints.fixed(bounds.width, bounds.height), probe.componentMeasureConstraints.last())
                assertSame(component, probe.componentNodes.getValue(ExternalNodeId.Root))
                assertEquals(bounds, tree.semantics().single().bounds)
                assertEquals(
                    bounds,
                    tree
                        .paint()
                        .filterIsInstance<DrawCommand.FillRectangle>()
                        .single()
                        .bounds,
                )
                assertEquals(
                    bounds,
                    tree
                        .paint()
                        .filterIsInstance<DrawCommand.PushClip>()
                        .single()
                        .bounds,
                )
                assertEquals(1, tree.paint().count { it is DrawCommand.PopClip })
                assertEquals(InputResult.Consumed, press(tree, bounds.left, bounds.top))
                assertEquals(InputResult.Consumed, press(tree, bounds.right - 1, bounds.bottom - 1))
                assertEquals(InputResult.Ignored, press(tree, bounds.right, bounds.top))
                assertEquals(InputResult.Ignored, press(tree, bounds.left, bounds.bottom))
                assertEquals(InputResult.Ignored, press(tree, bounds.left - 1, bounds.top))
            }
        } finally {
            tree.close()
        }
    }

    @Test
    fun changedThresholdRemeasuresTheRetainedPanelAndEqualThresholdStaysClean() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val constraints = Constraints.fixed(640, 360)
        tree.update(panel(probe))
        try {
            tree.measure(constraints)
            tree.layout()
            val component = probe.componentNodes.getValue(ExternalNodeId.Root)
            val initialMeasures = component.measures
            tree.update(panel(probe))
            tree.measure(constraints)
            tree.layout()
            assertEquals(initialMeasures, component.measures)

            tree.update(panel(probe, IntSize(641, 360)))
            tree.measure(constraints)
            tree.layout()
            assertSame(component, probe.componentNodes.getValue(ExternalNodeId.Root))
            assertEquals(Constraints.fixed(640, 360), probe.componentMeasureConstraints.last())
            assertEquals(IntRect(0, 0, 640, 360), tree.semantics().single().bounds)
        } finally {
            tree.close()
        }
    }

    @Test
    fun panelClipUsesMeasuredBoundsForOverflowingPaintAndInput() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                ResponsivePanel {
                    element(OverflowElement(ExternalElement(probe = probe)))
                }
            },
        )
        try {
            tree.measure(Constraints.fixed(640, 360))
            tree.layout()
            val commands = tree.paint()
            assertEquals(IntRect(160, 90, 480, 270), commands.filterIsInstance<DrawCommand.PushClip>().single().bounds)
            assertEquals(IntRect(476, 90, 484, 98), commands.filterIsInstance<DrawCommand.FillRectangle>().single().bounds)
            assertEquals(1, commands.count { it is DrawCommand.PopClip })
            assertEquals(InputResult.Consumed, press(tree, 479, 91))
            assertEquals(InputResult.Ignored, press(tree, 480, 91))
            assertEquals(1, probe.componentNodes.getValue(ExternalNodeId.Root).presses)
        } finally {
            tree.close()
        }
    }

    @Test
    fun zeroDimensionsRemainValidAndCannotReceivePointerInput() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(panel(probe))
        try {
            listOf(IntSize(0, 120), IntSize(120, 0), IntSize.Zero).forEach { viewport ->
                assertEquals(viewport, tree.measure(Constraints.fixed(viewport.width, viewport.height)))
                tree.layout()
                assertEquals(Constraints.fixed(viewport.width, viewport.height), probe.componentMeasureConstraints.last())
                assertEquals(InputResult.Ignored, press(tree, 0, 0))
            }
        } finally {
            tree.close()
        }
    }

    @Test
    fun invalidThresholdsAndUnboundedParentsFailExplicitly() {
        listOf(IntSize(0, 1), IntSize(1, 0)).forEach { threshold ->
            val tree = UiTree()
            try {
                assertThrows<IllegalArgumentException> { tree.update(panel(ExternalProbe(), threshold)) }
            } finally {
                tree.close()
            }
        }
        listOf(Constraints(maxWidth = 640), Constraints(maxHeight = 360)).forEach { constraints ->
            val tree = UiTree()
            tree.update(panel(ExternalProbe()))
            try {
                assertThrows<IllegalArgumentException> { tree.measure(constraints) }
            } finally {
                tree.close()
            }
        }
    }

    private fun panel(
        probe: ExternalProbe,
        minimumViewport: IntSize = IntSize(480, 270),
    ): Element =
        evaluateComponentTree {
            ResponsivePanel(minimumViewport) {
                element(ExternalElement(probe = probe, modifier = Modifier.Empty.fillMaxSize()))
            }
        }

    private fun press(
        tree: UiTree,
        x: Int,
        y: Int,
    ): InputResult = tree.dispatchPointer(PointerEvent.Press(IntOffset(x, y), PointerButton.Primary))

    private class OverflowElement(
        child: Element,
    ) : Element(identity = ElementIdentity.Positional, type = TYPE, children = listOf(child)) {
        companion object {
            val TYPE: ElementType<OverflowElement, OverflowNode> =
                ElementType(
                    elementClass = OverflowElement::class,
                    nodeClass = OverflowNode::class,
                    validateLocal = {},
                    createNode = { OverflowNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class OverflowNode :
        Node(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, Constraints.fixed(8, 8))
            return constraints.constrain(IntSize(constraints.maxWidth, constraints.maxHeight))
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, IntOffset(scope.size.width - 4, 0))
        }
    }
}
