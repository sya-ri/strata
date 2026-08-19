package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.node.Node

/**
 * Stores one owned node, its immutable description, and retained pipeline state.
 *
 * The runtime owns this storage until cleanup completes.
 * Lifecycle attempt flags make cleanup idempotent after a failed operation.
 */
internal class RetainedNode(
    var element: Element,
    node: Node,
    var logicalParent: RetainedNode?,
) : RetainedEntry(node) {
    override val effectiveChildCount: Int
        get() = children.size

    /**
     * Active modifier entries owned by this logical component.
     */
    val modifiers: MutableList<RetainedModifier> = ArrayList()

    /**
     * Direct children in declared order.
     */
    val children: MutableList<RetainedNode> = ArrayList()

    override fun effectiveChildAt(index: Int): RetainedEntry = children[index].effectiveRoot

    /**
     * Outermost retained entry that represents this logical component in the pipeline tree.
     */
    val effectiveRoot: RetainedEntry
        get() = modifiers.firstOrNull() ?: this
}
