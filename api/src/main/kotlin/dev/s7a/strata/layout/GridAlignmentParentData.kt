package dev.s7a.strata.layout

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * Internal active grid-cell placement parent-data modifier implementation.
 */
internal object GridAlignmentParentData {
    /**
     * Immutable grid child alignment data.
     *
     * @property alignment placement inside the measured cell.
     */
    internal data class Data(
        val alignment: Alignment,
    )

    /**
     * Stable referential key for grid alignment data.
     */
    internal val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

    /**
     * Immutable grid alignment modifier description.
     *
     * @property data the grid child alignment value.
     */
    internal data class Element(
        val data: Data,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained grid alignment modifier node.
     *
     * @param data the initial grid child alignment value.
     */
    internal class Node(
        private var data: Data,
    ) : ModifierNode(),
        ParentDataModifierNode<Data> {
        override val parentDataKey: ParentDataKey<Data>
            get() = KEY

        override fun parentData(): Data = data

        /**
         * Updates the retained grid child alignment.
         *
         * @param next the incoming value.
         * @return layout invalidation when the value changed.
         */
        internal fun update(next: Data): DirtyMask {
            val changed = data != next
            data = next
            return if (changed) DirtyMask.of(DirtyPhase.Layout) else DirtyMask.None
        }
    }

    /**
     * Stable grid alignment modifier token.
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
