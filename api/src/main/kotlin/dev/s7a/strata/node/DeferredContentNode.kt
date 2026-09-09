package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Deferred declaration capability evaluated before frame-cache selection, without forcing measurement.
 * The runtime evaluates pending content parent-first and reconciles its returned children before geometry.
 * Implementations clear reasons after successful evaluation; cached child access does not count as evaluation.
 */
@InternalStrataRuntimeApi
public interface DeferredContentNode : DynamicChildrenNode {
    /**
     * Diagnostic category independent of concrete component implementation classes.
     */
    public val contentKind: ContentKind

    /**
     * Current owner-thread evaluation reasons; callers borrow this set and must neither mutate nor retain it.
     */
    public val pendingContentReasons: Set<ContentInvalidation>
}
