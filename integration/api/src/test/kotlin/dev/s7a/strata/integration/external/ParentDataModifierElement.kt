package dev.s7a.strata.integration.external

import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase

/**
 * External immutable parent-data modifier description.
 *
 * @property probe the external observation owner.
 * @property value the immutable parent-data value.
 * @property parentDataKey the referential key owned by this description.
 * @property throwOnRead whether the retained provider throws when selected.
 * @property throwOnLayoutOnly whether the retained provider throws only for a layout query.
 * @property queryChild whether this provider queries its inner virtual child.
 * @property queryKey the key used by the optional inner-child query.
 */
public data class ParentDataModifierElement public constructor(
    public val probe: ParentDataProbe,
    public val value: ParentDataValue = ParentDataValue(0),
    public val parentDataKey: ParentDataKey<ParentDataValue> = KEY,
    public val throwOnRead: Boolean = false,
    public val throwOnLayoutOnly: Boolean = false,
    public val queryChild: Boolean = false,
    public val queryKey: ParentDataKey<ParentDataValue> = KEY,
) : ModifierElement {
    /**
     * The stable modifier type token.
     */
    override val type: ModifierNodeType<ParentDataModifierElement, ParentDataProviderNode>
        get() = TYPE

    /**
     * Stable token and typed hooks for this external description.
     */
    public companion object {
        /**
         * A shared key used by the external parent-data tests.
         */
        public val KEY: ParentDataKey<ParentDataValue> = ParentDataKey(ParentDataValue::class)

        /**
         * A distinct key with the same runtime type used for identity tests.
         */
        public val OTHER_KEY: ParentDataKey<ParentDataValue> = ParentDataKey(ParentDataValue::class)

        /**
         * Stable singleton modifier type token.
         */
        public val TYPE: ModifierNodeType<ParentDataModifierElement, ParentDataProviderNode> =
            ModifierNodeType(
                elementClass = ParentDataModifierElement::class,
                nodeClass = ParentDataProviderNode::class,
                validateLocal = { },
                createNode = { element ->
                    ParentDataProviderNode(element.probe).also { node ->
                        node.value = element.value
                        node.parentDataKey = element.parentDataKey
                        node.throwOnRead = element.throwOnRead
                        node.throwOnLayoutOnly = element.throwOnLayoutOnly
                        node.queryChild = element.queryChild
                        node.queryKey = element.queryKey
                        element.probe.providers += node
                    }
                },
                updateNode = { previous, current, node ->
                    node.value = current.value
                    node.parentDataKey = current.parentDataKey
                    node.throwOnRead = current.throwOnRead
                    node.throwOnLayoutOnly = current.throwOnLayoutOnly
                    node.queryChild = current.queryChild
                    node.queryKey = current.queryKey
                    val changed = previous != current
                    if (changed) {
                        DirtyMask.of(DirtyPhase.Measure)
                    } else {
                        DirtyMask.None
                    }
                },
            )
    }
}
