package dev.s7a.strata.runtime

import dev.s7a.strata.node.Node
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tracks ownership claims by node identity inside one tree build.
 *
 * Node equality is deliberately ignored because a user node may define value equality unrelated to ownership.
 */
internal class NodeOwnershipRegistry {
    private val nodes: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * Claims [node] for this tree's retained ownership.
     *
     * @throws IllegalStateException when another retained node already owns the same instance.
     */
    fun claim(
        node: Node,
    ) {
        check(nodes.contains(node).not()) { "The node instance is already runtime-owned by this tree." }
        nodes.add(node)
    }

    /**
     * Releases [node] from this tree's local ownership view.
     *
     * @param node the node whose claim is released.
     */
    fun release(node: Node) {
        nodes.remove(node)
    }

    /**
     * Clears this tree's local ownership view before cleanup callbacks begin.
     */
    fun clear() {
        nodes.clear()
    }
}
