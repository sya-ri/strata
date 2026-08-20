package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * Adversarial component node used to prove that modifier parent-data traversal stops before components.
 *
 * @param probe the parent-data observations updated when the invalid capability is invoked.
 */
public class ComponentParentDataNode internal constructor(
    private val probe: ParentDataProbe,
) : Node(),
    MeasureNode,
    LayoutNode,
    ParentDataModifierNode<ParentDataValue> {
    override val parentDataKey: ParentDataKey<ParentDataValue> = ParentDataModifierElement.KEY

    override fun parentData(): ParentDataValue {
        probe.componentProviderReads += 1
        return ParentDataValue(13)
    }

    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        probe.events += ParentDataEvent.ComponentMeasure
        return if (0 < scope.childCount) {
            scope.measureChild(0, constraints)
        } else {
            constraints.constrain(IntSize.Zero)
        }
    }

    override fun layout(scope: LayoutScope) {
        probe.events += ParentDataEvent.ComponentLayout
        if (0 < scope.childCount) {
            scope.placeChild(0, IntOffset.Zero)
        }
    }
}
