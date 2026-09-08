package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Nullable diagnostic collection shared by the session's retained processing stages.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RenderMonitoring {
    private var nextId = 1L

    /**
     * Allocates identities only while enabled; IDs are never reused within this tree's lifetime.
     */
    fun allocateId(): Long = nextId++

    /**
     * The active collector, absent during ordinary execution.
     */
    var collector: RenderMonitorImpl? = null

    /**
     * Current host operation; no history or timestamps are retained.
     */
    var operation: UiRenderOperation = UiRenderOperation.Other

    /**
     * Counts actual work without constructing diagnostic objects when disabled.
     */
    fun record(
        metric: UiRenderMetric,
        entry: RetainedEntry? = null,
    ) {
        collector?.record(metric, operation, entry)
    }

    /**
     * Registers a newly owned entry only while monitoring is enabled.
     */
    fun created(entry: RetainedEntry) {
        collector?.created(entry, operation)
    }

    /**
     * Releases entry references after its final lifecycle attempt.
     */
    fun disposed(entry: RetainedEntry) {
        collector?.disposed(entry, operation)
    }

    /**
     * Adjusts the current subscription gauge alongside the interval counters.
     */
    fun subscription(opened: Boolean) {
        collector?.subscription(opened, operation)
    }

    /**
     * Ends collection at terminal cleanup without calling the public boundary guard.
     */
    fun release() {
        collector?.release()
        collector = null
    }

    /**
     * Preserves detached final evidence while cleanup still releases every runtime reference.
     */
    fun failed() {
        collector?.markFailed()
    }
}
