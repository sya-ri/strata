package dev.s7a.strata.layout

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * Internal active row cross-axis parent-data modifier implementation.
 */
internal object RowAlignmentParentData {
    /**
     * Immutable row child alignment data.
     *
     * @property alignment the child vertical placement override.
     */
    internal data class Data(
        val alignment: VerticalAlignment,
    )

    /**
     * Stable referential key for row alignment data.
     */
    internal val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

    /**
     * Immutable row alignment modifier description.
     *
     * @property data the row child alignment value.
     */
    internal data class Element(
        val data: Data,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained row alignment modifier node.
     *
     * @param data the initial row child alignment value.
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
         * Updates the retained row child alignment.
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
     * Stable row alignment modifier token.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element.data) },
            updateNode = { _, current, node -> node.update(current.data) },
        )
}
