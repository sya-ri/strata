package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detached bounded interval evidence. An overflowed snapshot cannot establish a complete per-node assertion.
 */
@InternalStrataRuntimeApi
public data class UiRenderSnapshot(
    public val counts: Map<UiRenderMetric, Long>,
    public val operations: Map<UiRenderOperation, Map<UiRenderMetric, Long>>,
    public val nodes: List<UiRenderNodeSnapshot>,
    public val activeSubscriptions: Int,
    public val overflowed: Boolean,
)
