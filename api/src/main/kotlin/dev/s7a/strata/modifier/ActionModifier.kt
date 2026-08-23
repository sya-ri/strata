package dev.s7a.strata.modifier

import dev.s7a.strata.action.ActionHandler
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode

/**
 * Internal pass-through node retaining one typed component-action callback.
 */
internal object ActionModifier {
    /**
     * Immutable Modifier description retaining one type-erased action handler.
     */
    internal class Element(
        val handler: ActionHandler,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Pass-through retained node whose handler is read from its immutable description chain.
     */
    internal class Node : ModifierNode()

    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { _ -> Node() },
            updateNode = { _, _, _ -> DirtyMask.None },
        )
}
