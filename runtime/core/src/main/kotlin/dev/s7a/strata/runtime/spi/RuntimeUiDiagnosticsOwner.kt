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
}
