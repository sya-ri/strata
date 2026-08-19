package dev.s7a.strata.node

import dev.s7a.strata.semantics.SemanticsScope

/**
 * A node that emits unresolved semantics entries.
 *
 * One successful callback produces the node's complete local semantics payload.
 * The runtime retains that payload until semantics invalidation and combines it with the node's current accumulated bounds.
 */
public interface SemanticsNode {
    /**
     * Emits this node's semantics on the owning tree thread.
     *
     * The scope is valid only for the duration of this callback.
     * Access after return or from another thread fails.
     * If a callback or scope operation throws after pipeline work begins, the owning tree is poisoned and cleanup is attempted.
     * This happens only when the exception escapes this callback.
     *
     * @param scope the current semantics collector.
     */
    public fun semantics(scope: SemanticsScope)
}
