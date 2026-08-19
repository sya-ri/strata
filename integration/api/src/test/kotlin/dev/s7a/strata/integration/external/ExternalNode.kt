package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * A typed external retained node used by the integration test.
 */
public class ExternalNode internal constructor(
    private val probe: ExternalProbe,
    private val id: ExternalNodeId,
) : Node(),
    MeasureNode,
    LayoutNode,
    PaintNode,
    PointerInputNode,
    SemanticsNode,
    LifecycleNode {
    /**
     * The current preferred width.
     */
    internal var width: Int = 4

    /**
     * The current fill color.
     */
    internal var color: ArgbColor = ArgbColor(0xFF00FF00.toInt())

    /**
     * The current unresolved semantics label.
     */
    internal var label: UiText = UiText.Literal("external")

    /**
     * Number of measure passes observed.
     */
    internal var measures: Int = 0

    /**
     * Number of paint passes observed.
     */
    internal var paints: Int = 0

    /**
     * Number of consumed presses observed.
     */
    internal var presses: Int = 0

    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        measures += 1
        probe.componentChildCounts += scope.childCount
        val childSize = if (0 < scope.childCount) scope.measureChild(0, constraints) else IntSize.Zero
        return constraints.constrain(IntSize(width, childSize.height.coerceAtLeast(4)))
    }

    override fun layout(scope: LayoutScope) {
        if (0 < scope.childCount) {
            scope.placeChild(0, IntOffset.Zero)
        }
    }

    override fun paint(scope: PaintScope) {
        paints += 1
        scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
    }

    override fun onPointerEvent(
        event: PointerEvent,
        localPosition: IntOffset,
    ): InputResult {
        if (event is PointerEvent.Press) {
            presses += 1
            return InputResult.Consumed
        }
        return InputResult.Ignored
    }

    override fun semantics(scope: SemanticsScope) {
        scope.emit(Semantics(label = label))
    }

    override fun attach() {
        probe.lifecycle += ExternalLifecycleEvent.Attach(id)
    }

    override fun detach() {
        probe.lifecycle += ExternalLifecycleEvent.Detach(id)
    }

    override fun dispose() {
        probe.lifecycle += ExternalLifecycleEvent.Dispose(id)
    }
}
