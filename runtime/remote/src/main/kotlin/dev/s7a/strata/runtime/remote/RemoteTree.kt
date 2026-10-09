@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Validated immutable rooted declaration tree; each component and modifier has a unique identity.
 * Construction rejects cycles, shared children, unreachable records, and excess depth or node count.
 */
public class RemoteTree private constructor(
    public val root: Long,
    index: Map<Long, RemoteNode>,
    limits: RemoteLimits,
) {
    /**
     * Read-only current records in their original public-input or private-candidate insertion order.
     */
    public val nodes: Map<Long, RemoteNode>

    /**
     * Independently snapshots the caller's collection before complete topology and declaration validation.
     * Neither collection storage nor a mutable index is shared with the caller.
     */
    public constructor(
        root: Long,
        nodes: Collection<RemoteNode>,
        limits: RemoteLimits = RemoteLimits(),
    ) : this(root, snapshot(nodes, limits), limits)

    init {
        require(index.size <= limits.treeNodes) { "Remote tree exceeds its node limit." }
        var visited = 0
        val identities = HashSet<Long>()
        val pendingIdentities = LongArray(maxOf(1, index.size))
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

    /**
     * Owns construction of candidate indexes; no caller-supplied map can enter the owned route.
     * Keys are always derived from immutable node declarations by these producers.
     */
    @InternalStrataRuntimeApi
    internal companion object {
        /**
         * Clones the previous index, applies an immutable patch, and transfers only that private candidate.
         * No mutable alias escapes; the ordinary complete validator runs before any result is published.
         * Empty patches still pass every negotiated bound and topology/declaration check.
         */
        @JvmSynthetic
        internal fun applyOwnedPatch(
            previous: RemoteTree,
            patch: RemotePatch,
            limits: RemoteLimits,
        ): RemoteTree {
            require(patch.changed.size <= limits.treeNodes && patch.removed.size <= limits.treeNodes) { "Remote patch exceeds its limit." }
            require(patch.removed.all(previous.nodes::containsKey)) { "Remote patch removes an unknown node." }
            val candidate = previous.nodes.toMutableMap()
            patch.removed.forEach(candidate::remove)
            patch.changed.forEach { candidate[it.declaration.identity] = it }
            return RemoteTree(patch.root, candidate, limits)
        }

        private fun snapshot(
            nodes: Collection<RemoteNode>,
            limits: RemoteLimits,
        ): Map<Long, RemoteNode> {
            require(nodes.size <= limits.treeNodes) { "Remote tree exceeds its node limit." }
            val index = nodes.associateBy { it.declaration.identity }
            require(index.size == nodes.size) { "Duplicate remote component identity." }
            return index
        }
    }
}
