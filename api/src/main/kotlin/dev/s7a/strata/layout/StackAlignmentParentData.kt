package dev.s7a.strata.layout

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode

/**
 * Internal active stack placement parent-data modifier implementation.
 */
internal object StackAlignmentParentData {
    /**
     * Immutable stack child alignment data.
     *
     * @property alignment the child two-axis placement override.
     */
    internal data class Data(
        val alignment: Alignment,
    )

    /**
     * Stable referential key for stack alignment data.
     */
    internal val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

    /**
     * Immutable stack alignment modifier description.
     *
     * @property data the stack child alignment value.
     */
    internal data class Element(
        val data: Data,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retained stack alignment modifier node.
     *
     * @param data the initial stack child alignment value.
     */
    internal class Node(
        private var data: Data,
    ) : ModifierNode(),
        ParentDataModifierNode<Data> {
        override val parentDataKey: ParentDataKey<Data>
            get() = KEY

        override fun parentData(): Data = data

        /**
         * Updates the retained stack child alignment.
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
     * Stable stack alignment modifier token.
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
