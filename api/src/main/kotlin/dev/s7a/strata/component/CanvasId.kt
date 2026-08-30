package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable scalar identity of one retained canvas, independent of its current source or attachment.
 *
 * The canvas allocates this identity once on its owning tree thread and never reuses it.
 * Native owners may use the value to account for active and retired resources together without retaining the node, source, or screen.
 * Reading or comparing an identity is thread-safe and does not resolve a live native resource.
 *
 * @property value process-unique positive identity value.
 */
@InternalStrataRuntimeApi
@JvmInline
public value class CanvasId internal constructor(
    public val value: Long,
)
