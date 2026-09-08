package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detached immutable diagnostic record, containing no source values, keys, node references, or callbacks.
 */
@InternalStrataRuntimeApi
public data class UiRenderNodeSnapshot(
    public val id: UiRenderNodeId,
    public val parentId: UiRenderNodeId?,
    public val kind: UiRenderNodeKind,
    public val typeName: String,
    public val retired: Boolean,
    public val counts: Map<UiRenderMetric, Long>,
    public val operations: Map<UiRenderOperation, Map<UiRenderMetric, Long>>,
)
