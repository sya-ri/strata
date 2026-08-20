package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * External active provider node used by parent-data integration tests.
 *
 * @param probe the observation and failure owner retained for the complete node lifetime.
 */
public class ParentDataProviderNode internal constructor(
    private val probe: ParentDataProbe,
) : ModifierNode(),
    ParentDataModifierNode<ParentDataValue>,
    LifecycleNode {
    /**
     * Current value installed by the typed modifier update hook.
     */
    internal var value: ParentDataValue = ParentDataValue(0)

    /**
     * Current referential key installed by the typed modifier update hook.
     */
    override var parentDataKey: ParentDataKey<ParentDataValue> = ParentDataModifierElement.KEY

    /**
     * Whether selecting this provider should throw the probe's sentinel.
     */
    internal var throwOnRead: Boolean = false

    /**
     * Whether selecting this provider should throw only during a layout query.
     */
    internal var throwOnLayoutOnly: Boolean = false

    /**
     * Whether this provider queries its inner virtual child during measure and layout.
     */
    internal var queryChild: Boolean = false

    /**
     * Key used by the optional inner-child query.
     */
    internal var queryKey: ParentDataKey<ParentDataValue> = ParentDataModifierElement.KEY

    /**
     * Number of detach attempts observed for this provider.
     */
    internal var detachCount: Int = 0

    /**
     * Number of dispose attempts observed for this provider.
     */
    internal var disposeCount: Int = 0

    /**
     * Number of times this provider was selected and read.
     */
    internal var readCount: Int = 0

    /**
     * Measures the virtual child after optionally querying its inner parent data.
     */
    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        if (queryChild && 0 < scope.childCount) {
            probe.modifierMeasureValues += scope.childParentData(0, queryKey)
        }
        return super.measure(scope, constraints)
    }

    /**
     * Places the virtual child after optionally querying its inner parent data.
     */
    override fun layout(scope: LayoutScope) {
        if (queryChild && 0 < scope.childCount) {
            probe.layoutParentDataQuery = true
            try {
                probe.modifierLayoutValues += scope.childParentData(0, queryKey)
            } finally {
                probe.layoutParentDataQuery = false
            }
        }
        super.layout(scope)
    }

    /**
     * Supplies the current value only when this provider is selected by a parent scope.
     */
    override fun parentData(): ParentDataValue {
        readCount += 1
        probe.events += ParentDataEvent.ProviderRead
        if (throwOnRead || (throwOnLayoutOnly && probe.layoutParentDataQuery)) {
            throw probe.providerFailure
        }
        return value
    }

    /**
     * Records provider attachment without acquiring external resources.
     */
    override fun attach(): Unit = Unit

    /**
     * Records provider cleanup.
     */
    override fun detach() {
        detachCount += 1
    }

    /**
     * Records provider retirement.
     */
    override fun dispose() {
        disposeCount += 1
    }
}
