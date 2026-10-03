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
        var visited = 0
        val identities = HashSet<Long>()
        val pendingIdentities = LongArray(maxOf(1, nodes.size))
        val pendingDepths = IntArray(pendingIdentities.size)
        var pending = 1
        pendingIdentities[0] = root
        while (0 < pending) {
            pending -= 1
            val identity = pendingIdentities[pending]
            val depth = pendingDepths[pending]
            require(depth < limits.valueDepth) { "Remote tree exceeds its depth limit." }
            require(identities.add(identity)) { "Remote tree contains shared or cyclic identities." }
            visited += 1
            val node = requireNotNull(index[identity]) { "Remote tree references an absent child." }
            require(node.modifiers.size <= limits.collectionEntries) { "Remote modifier count exceeds its limit." }
            node.modifiers.forEach { require(identities.add(it.identity)) { "Duplicate remote modifier identity." } }
            require(identities.size <= limits.collectionEntries) { "Remote declaration count exceeds its limit." }
            require(node.children.size <= limits.treeNodes) { "Remote child count exceeds its limit." }
            require(node.children.size <= index.size - visited - pending) { "Remote tree has excess child references." }
            node.children.forEach { child ->
                pendingIdentities[pending] = child
                pendingDepths[pending] = depth + 1
                pending += 1
            }
        }
        require(visited == index.size) { "Remote tree contains unreachable records." }
        this.nodes = Collections.unmodifiableMap(index)
    }

    override fun equals(other: Any?): Boolean = other is RemoteTree && root == other.root && nodes == other.nodes

    override fun hashCode(): Int = 31 * root.hashCode() + nodes.hashCode()
}
