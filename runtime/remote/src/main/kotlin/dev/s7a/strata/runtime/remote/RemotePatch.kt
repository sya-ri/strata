@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Immutable changes between two successive complete declaration trees.
 * Applying a patch validates the entire candidate before returning it, leaving the previous tree intact on failure.
 */
public class RemotePatch(
    public val root: Long,
    changed: Collection<RemoteNode>,
    removed: Collection<Long>,
) {
    public val changed: List<RemoteNode> = Collections.unmodifiableList(changed.toList())
    public val removed: Set<Long> = Collections.unmodifiableSet(removed.toSet())

    init {
        val identities = HashSet<Long>()
        require(this.changed.all { identities.add(it.declaration.identity) }) { "Duplicate patch change." }
        require(this.removed.size == removed.size) { "Duplicate patch removal." }
        require(this.changed.none { it.declaration.identity in this.removed }) { "A patch both changes and removes an identity." }
    }

    /**
     * Builds and validates the candidate without changing [previous].
     */
    public fun apply(
        previous: RemoteTree,
        limits: RemoteLimits = RemoteLimits(),
    ): RemoteTree = RemoteTree.applyOwnedPatch(previous, this, limits)

    /**
     * Factories for detached tree differences.
     */
    public companion object {
        /**
         * Emits only changed records and removed identities, preserving ordered child links.
         */
        public fun between(
            previous: RemoteTree,
            current: RemoteTree,
        ): RemotePatch =
            RemotePatch(
                current.root,
                current.nodes.values.filter { previous.nodes[it.declaration.identity] != it },
                previous.nodes.keys - current.nodes.keys,
            )
    }
}
