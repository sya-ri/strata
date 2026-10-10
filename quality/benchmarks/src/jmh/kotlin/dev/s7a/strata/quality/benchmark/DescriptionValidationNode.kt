package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * Retained external primitive that measures/places every child and emits one ordered rectangle and semantic entry.
 */
internal class DescriptionValidationNode(
    private val index: Int,
    var value: Int,
) : Node(),
    MeasureNode,
    LayoutNode,
    PaintNode,
    SemanticsNode {
    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        for (child in 0 until scope.childCount) scope.measureChild(child, CHILD_CONSTRAINTS)
        return constraints.constrain(IntSize(value + 1, value + 1))
    }

    override fun layout(scope: LayoutScope) {
        for (child in 0 until scope.childCount) scope.placeChild(child, IntOffset.Zero)
    }

    override fun paint(scope: PaintScope) {
        scope.fillRectangle(IntRect(0, 0, value + 1, value + 1), ArgbColor(if (value == 0) 0xFF112233.toInt() else 0xFF445566.toInt()))
    }

    override fun semantics(scope: SemanticsScope) {
        scope.emit(Semantics(label = UiText.Literal("$index:$value")))
    }

    /**
     * Fixed child constraints keep every declared descendant placed and visible.
     */
    private companion object {
        val CHILD_CONSTRAINTS = Constraints(maxWidth = 2, maxHeight = 2)
    }
}
