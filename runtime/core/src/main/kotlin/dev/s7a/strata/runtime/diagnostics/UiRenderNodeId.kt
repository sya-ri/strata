package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.jvm.JvmInline

/**
 * Monotonic identity scoped to one retained session, stable across monitor restarts while the node survives.
 * Contains no reference to the application node or its key.
 */
@InternalStrataRuntimeApi
@JvmInline
public value class UiRenderNodeId(
    public val value: Long,
)
