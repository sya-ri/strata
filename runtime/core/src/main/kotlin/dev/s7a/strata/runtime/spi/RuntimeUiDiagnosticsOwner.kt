package dev.s7a.strata.runtime.spi

import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Test and adapter capability for opt-in work measurement on a real retained screen, without changing its declaration.
 */
@InternalStrataRuntimeApi
public interface RuntimeUiDiagnosticsOwner {
    /**
     * Starts owner-thread monitoring at an operation boundary with existing nodes as the baseline.
     * A second active monitor, reentrant access, or an unavailable tree is rejected without changing the screen.
     * The caller closes the monitor; terminal tree cleanup also releases it automatically.
     */
    public fun startRenderMonitoring(): UiRenderMonitor

    /**
     * Starts monitoring with an explicit bounded node-record capacity for large test fixtures.
     * Capacity must be in 1..65,536 and applies to live plus retired identities in one checkpoint interval.
     * Adapters without this capability retain their existing 4,096-record entry point and reject other capacities.
     */
    public fun startRenderMonitoring(maxNodeRecords: Int): UiRenderMonitor {
        require(maxNodeRecords in 1..65_536) { "Invalid render monitoring node-record capacity." }
        if (maxNodeRecords != 4096) throw UnsupportedOperationException("This diagnostics owner does not support a custom node-record capacity.")
        return startRenderMonitoring()
    }
}
