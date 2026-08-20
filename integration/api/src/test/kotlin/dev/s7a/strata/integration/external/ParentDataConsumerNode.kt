package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
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
 * External component node that queries parent data before measuring and laying out its child.
 *
 * @param probe the observation owner retained for the complete node lifetime.
 */
public class ParentDataConsumerNode internal constructor(
    private val probe: ParentDataProbe,
) : Node(),
    MeasureNode,
    LayoutNode,
    PaintNode,
    SemanticsNode {
    /**
     * Current referential key installed by the typed element update hook.
     */
    internal var parentDataKey: ParentDataKey<ParentDataValue> = ParentDataModifierElement.KEY

    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        check(scope.childCount == 1) { "The parent-data consumer requires one direct child." }
        if (probe.captureScopes) {
            probe.measureScope = scope
            probe.measureEntered.countDown()
        }
        if (probe.blockMeasure) {
            probe.measureRelease.await()
        }
        probe.consumerMeasureCalls += 1
        if (probe.queryInvalidIndex) {
            probe.measureInvalidIndexFailure =
                runCatching { scope.childParentData(scope.childCount, parentDataKey) }.exceptionOrNull()
        }
        probe.consumerMeasureValues += scope.childParentData(0, parentDataKey)
        val childSize = scope.measureChild(0, constraints)
        probe.consumerMeasureChildCalls += 1
        return constraints.constrain(childSize)
    }

    override fun layout(scope: LayoutScope) {
        check(scope.childCount == 1) { "The parent-data consumer requires one direct child." }
        if (probe.captureScopes) {
            probe.layoutScope = scope
            probe.layoutEntered.countDown()
        }
        if (probe.blockLayout) {
            probe.layoutRelease.await()
        }
        probe.consumerLayoutCalls += 1
        if (probe.queryInvalidIndex) {
            probe.layoutInvalidIndexFailure =
                runCatching { scope.childParentData(scope.childCount, parentDataKey) }.exceptionOrNull()
        }
        probe.layoutParentDataQuery = true
        try {
            probe.consumerLayoutValues += scope.childParentData(0, parentDataKey)
        } finally {
            probe.layoutParentDataQuery = false
        }
        scope.placeChild(0, IntOffset.Zero)
        probe.consumerPlaceChildCalls += 1
    }

    override fun paint(scope: PaintScope) {
        probe.consumerPaintCalls += 1
        scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(0xFF101010.toInt()))
    }

    override fun semantics(scope: SemanticsScope) {
        probe.consumerSemanticsCalls += 1
        scope.emit(Semantics(label = UiText.Literal("parent-data-consumer")))
    }
}
