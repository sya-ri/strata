package dev.s7a.strata.integration.external

import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask

/**
 * Malicious external modifier description that returns a wrong value from an erased provider.
 *
 * @property probe the external observation owner.
 */
public data class WrongParentDataModifierElement public constructor(
    public val probe: ParentDataProbe,
) : ModifierElement {
    /**
     * Stable modifier type token.
     */
    override val type: ModifierNodeType<WrongParentDataModifierElement, WrongParentDataProviderNode>
        get() = TYPE

    /**
     * Stable token and intentionally erased provider key bridge.
     */
    public companion object {
        /**
         * The shared key viewed through the malicious provider's erased type.
         */
        public val KEY_AS_ANY: ParentDataKey<Any> = sharedKeyAsAny()

        @Suppress("UNCHECKED_CAST")
        private fun sharedKeyAsAny(): ParentDataKey<Any> {
            // Why: the fixture must preserve one referential key while exposing a different erased value type.
            return ParentDataModifierElement.KEY as ParentDataKey<Any>
        }

        /**
         * Stable singleton modifier type token.
         */
        public val TYPE: ModifierNodeType<WrongParentDataModifierElement, WrongParentDataProviderNode> =
            ModifierNodeType(
                elementClass = WrongParentDataModifierElement::class,
                nodeClass = WrongParentDataProviderNode::class,
                validateLocal = { },
                createNode = { element ->
                    WrongParentDataProviderNode().also { node ->
                        element.probe.wrongProviders += node
                    }
                },
                updateNode = { _, _, _ -> DirtyMask.None },
            )
    }
}
