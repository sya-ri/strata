package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Fixed-size primitive counters; detached maps are allocated only for an explicit snapshot.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RenderWorkCounts {
    private val counts = LongArray(UiRenderMetric.entries.size * UiRenderOperation.entries.size)

    /**
     * Adds one actual operation.
     */
    fun record(
        metric: UiRenderMetric,
        operation: UiRenderOperation,
    ) {
        counts[operation.ordinal * UiRenderMetric.entries.size + metric.ordinal] += 1
    }

    /**
     * Clears the current interval in place.
     */
    fun clear() {
        counts.fill(0)
    }

    /**
     * Copies aggregate counts without retaining this mutable counter.
     */
    fun totals(): Map<UiRenderMetric, Long> =
        buildMap {
            UiRenderMetric.entries.forEach { metric ->
                put(metric, UiRenderOperation.entries.sumOf { operation -> count(metric, operation) })
            }
        }

    /**
     * Copies counts grouped by host operation.
     */
    fun operations(): Map<UiRenderOperation, Map<UiRenderMetric, Long>> =
        buildMap {
            UiRenderOperation.entries.forEach { operation ->
                put(
                    operation,
                    buildMap {
                        UiRenderMetric.entries.forEach { metric -> put(metric, count(metric, operation)) }
                    },
                )
            }
        }

    private fun count(
        metric: UiRenderMetric,
        operation: UiRenderOperation,
    ): Long = counts[operation.ordinal * UiRenderMetric.entries.size + metric.ordinal]
}
