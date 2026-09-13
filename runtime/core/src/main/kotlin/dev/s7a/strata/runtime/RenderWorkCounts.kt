package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.runtime.platform.Collections
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
    fun totals(): Map<UiRenderMetric, Long> {
        val result = LinkedHashMap<UiRenderMetric, Long>()
        UiRenderMetric.entries.forEach { metric ->
            result[metric] = UiRenderOperation.entries.sumOf { operation -> count(metric, operation) }
        }
        return Collections.unmodifiableMap(result)
    }

    /**
     * Copies counts grouped by host operation.
     */
    fun operations(): Map<UiRenderOperation, Map<UiRenderMetric, Long>> {
        val result = LinkedHashMap<UiRenderOperation, Map<UiRenderMetric, Long>>()
        UiRenderOperation.entries.forEach { operation ->
            val metrics = LinkedHashMap<UiRenderMetric, Long>()
            UiRenderMetric.entries.forEach { metric -> metrics[metric] = count(metric, operation) }
            result[operation] = Collections.unmodifiableMap(metrics)
        }
        return Collections.unmodifiableMap(result)
    }

    private fun count(
        metric: UiRenderMetric,
        operation: UiRenderOperation,
    ): Long = counts[operation.ordinal * UiRenderMetric.entries.size + metric.ordinal]
}
