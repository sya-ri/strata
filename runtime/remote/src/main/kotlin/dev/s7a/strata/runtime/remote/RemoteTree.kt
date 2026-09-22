package dev.s7a.strata.runtime.remote

import java.util.Collections

/**
 * Validated immutable rooted declaration tree; each component and modifier has a unique identity.
 * Construction rejects cycles, shared children, unreachable records, and excess depth or node count.
 */
public class RemoteTree(
    public val root: Long,
    nodes: Collection<RemoteNode>,
    limits: RemoteLimits = RemoteLimits(),
) {
    public val nodes: Map<Long, RemoteNode>

    init {
        require(nodes.size <= limits.treeNodes) { "Remote tree exceeds its node limit." }
        val index = nodes.associateBy { it.declaration.identity }
        require(index.size == nodes.size) { "Duplicate remote component identity." }
        val visited = mutableSetOf<Long>()
        val identities = mutableSetOf<Long>()
        val pending = ArrayDeque<Pair<Long, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (identity, depth) = pending.removeLast()
            require(depth < limits.valueDepth) { "Remote tree exceeds its depth limit." }
            require(visited.add(identity) && identities.add(identity)) { "Remote tree contains shared or cyclic identities." }
            val node = requireNotNull(index[identity]) { "Remote tree references an absent child." }
            require(node.modifiers.size <= limits.collectionEntries) { "Remote modifier count exceeds its limit." }
            node.modifiers.forEach { require(identities.add(it.identity)) { "Duplicate remote modifier identity." } }
            require(identities.size <= limits.collectionEntries) { "Remote declaration count exceeds its limit." }
            require(node.children.size <= limits.treeNodes) { "Remote child count exceeds its limit." }
            require(node.children.size <= index.size - visited.size - pending.size) { "Remote tree has excess child references." }
            node.children.forEach { pending.add(it to depth + 1) }
        }
        require(visited.size == index.size) { "Remote tree contains unreachable records." }
        this.nodes = Collections.unmodifiableMap(index)
    }

    override fun equals(other: Any?): Boolean = other is RemoteTree && root == other.root && nodes == other.nodes

    override fun hashCode(): Int = 31 * root.hashCode() + nodes.hashCode()
}
