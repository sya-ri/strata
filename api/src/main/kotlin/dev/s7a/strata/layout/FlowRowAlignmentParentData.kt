package dev.s7a.strata.layout

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * Internal active flow-row alignment modifier, confined to the owning tree thread.
 *
 * Each node retains only its current immutable alignment value and borrows no external resources.
 */
internal object FlowRowAlignmentParentData {
    /**
     * Immutable alignment within one measured flow row.
     *
     * @property alignment the child vertical placement override.
     */
    internal data class Data(
        val alignment: VerticalAlignment,
    )

    /**
     * Stable referential key consumed only by flow-row parents.
     */
    internal val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

    /**
     * Immutable flow-row alignment modifier description.
     *
     * @property data the flow-row child alignment value.
     */
    internal data class Element(
        val data: Data,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained flow-row alignment modifier node owned by one tree.
     *
     * @param data the initial row-local child alignment value.
     */
    internal class Node(
        private var data: Data,
    ) : ModifierNode(),
        ParentDataModifierNode<Data> {
        override val parentDataKey: ParentDataKey<Data>
            get() = KEY

        override fun parentData(): Data = data

        /**
         * Updates the retained alignment on the owning tree thread.
         *
         * @param next the incoming value.
         * @return measurement invalidation when the value changed, matching other parent-data modifiers.
         */
        internal fun update(next: Data): DirtyMask {
            val changed = data != next
            data = next
            return if (changed) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
        }
    }

    /**
     * Stable flow-row alignment modifier token.
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
