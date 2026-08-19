package dev.s7a.strata.semantics

import dev.s7a.strata.node.SemanticsNode

/**
 * Semantics collector for one retained node.
 *
 * The scope is owned by the [SemanticsNode.semantics] callback and is valid only until that callback returns.
 * Every access must use the tree's owner thread.
 * Access after the callback or from another thread fails.
 * Emitted values are retained as the complete local payload in emission order.
 * Text remains unresolved until a platform adapter consumes the resulting entries.
 * The tree is poisoned once pipeline work has started only when a scope access exception escapes the owning semantics callback.
 */
public interface SemanticsScope {
    /**
     * Emits one immutable semantics payload for this node.
     *
     * @param semantics the unresolved semantics value.
     * @throws IllegalStateException when the scope is no longer valid.
     */
    public fun emit(semantics: Semantics)
}
