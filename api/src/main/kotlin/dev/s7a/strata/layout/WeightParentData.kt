package dev.s7a.strata.layout

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * Internal active weighted-child parent-data modifier implementation.
 */
internal object WeightParentData {
    /**
     * Immutable weighted-child parent data.
     *
     * @property weight the positive finite allocation weight.
     * @property fill whether the child receives the complete allocated slot.
     */
    internal data class Data(
        val weight: Float,
        val fill: Boolean,
    )

    /**
     * Stable referential key for weighted-child data.
     */
    internal val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

    /**
     * Immutable weighted-child modifier description.
     *
     * @property data the weighted-child value supplied to a parent layout.
     */
    internal data class Element(
        val data: Data,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained weighted-child modifier node.
     *
     * @param data the initial weighted-child value.
     */
    internal class Node(
        data: Data,
    ) : ModifierNode(),
        ParentDataModifierNode<Data> {
        private var data: Data = data

        override val parentDataKey: ParentDataKey<Data>
            get() = KEY

        override fun parentData(): Data = data

        /**
         * Updates the retained weighted-child value.
         *
         * @param next the incoming value.
         * @return measurement invalidation when the value changed.
         */
        internal fun update(next: Data): DirtyMask {
            val changed = data != next
            data = next
            return if (changed) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
        }
    }

    /**
     * Stable weighted-child modifier token.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element ->
                require(element.data.weight.isFinite() && 0 < element.data.weight) {
                    "Weight must be positive and finite."
                }
            },
            createNode = { element -> Node(element.data) },
            updateNode = { _, current, node -> node.update(current.data) },
        )
}
