package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import java.util.EnumMap

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
        val result = EnumMap<UiRenderMetric, Long>(UiRenderMetric::class.java)
        UiRenderMetric.entries.forEach { metric ->
            result[metric] = UiRenderOperation.entries.sumOf { operation -> count(metric, operation) }
        }
        return Collections.unmodifiableMap(result)
    }

    /**
     * Copies counts grouped by host operation.
     */
    fun operations(): Map<UiRenderOperation, Map<UiRenderMetric, Long>> {
        val result = EnumMap<UiRenderOperation, Map<UiRenderMetric, Long>>(UiRenderOperation::class.java)
        UiRenderOperation.entries.forEach { operation ->
            val metrics = EnumMap<UiRenderMetric, Long>(UiRenderMetric::class.java)
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
