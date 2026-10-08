package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.node.Node
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation

/**
 * Stores one owned node, its immutable description, and retained pipeline state.
 *
 * The runtime owns this storage until cleanup completes.
 * Lifecycle attempt flags make cleanup idempotent after a failed operation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedNode(
    var element: Element,
    node: Node,
    var logicalParent: RetainedNode?,
) : RetainedEntry(node) {
    /**
     * Last immutable dynamic-child list already validated and reconciled; cached access requires no sibling diff.
     */
    var dynamicDescriptions: List<Element>? = null

    /**
     * State reads of the current deferred callback, released before node cleanup.
     */
    var contentObservation: StateObservation? = null

    /**
     * One current fixed subtree snapshot shared with ancestors; opaque encoders never occupy this cache.
     * Description/projection/ordered-child changes invalidate it, and detach or cleanup clears it before callbacks.
     */
    var declarationSnapshot: RuntimeDeclaration? = null

    /**
     * Retires this description key and every ancestor key before replacement or removal callbacks can run.
     */
    fun invalidateDeclarationSnapshot() {
        var current: RetainedNode? = this
        // A cached ancestor requires each child to have its own fixed current snapshot.
        while (current != null && current.declarationSnapshot != null) {
            current.declarationSnapshot = null
            current = current.logicalParent
        }
    }

    /**
     * Releases the complete current snapshot set before session-scoped resources are detached.
     */
    fun releaseDeclarationSnapshots() {
        declarationSnapshot = null
        children.forEach(RetainedNode::releaseDeclarationSnapshots)
    }

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
