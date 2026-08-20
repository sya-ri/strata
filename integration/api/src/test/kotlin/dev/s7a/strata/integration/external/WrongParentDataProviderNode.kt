package dev.s7a.strata.integration.external

import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * External provider whose erased value is intentionally incompatible with the shared [ParentDataValue] key.
 */
public class WrongParentDataProviderNode internal constructor() :
    ModifierNode(),
    ParentDataModifierNode<Any>,
    LifecycleNode {
        /**
         * Current shared key viewed through the provider's erased value type.
         */
        override val parentDataKey: ParentDataKey<Any> = WrongParentDataModifierElement.KEY_AS_ANY

        /**
         * Number of detach callbacks observed.
         */
        internal var detachCount: Int = 0

        /**
         * Number of dispose callbacks observed.
         */
        internal var disposeCount: Int = 0

        override fun parentData(): Any = "wrong parent-data runtime value"

        override fun attach(): Unit = Unit

        override fun detach() {
            detachCount += 1
        }

        override fun dispose() {
            disposeCount += 1
        }
    }
