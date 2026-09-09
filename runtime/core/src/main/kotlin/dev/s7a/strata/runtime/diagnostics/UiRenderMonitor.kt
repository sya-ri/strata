package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread, opt-in work monitor with at most 4,096 node records per checkpoint interval and no frame history.
 * Existing nodes form a baseline rather than being reported as newly created. Snapshots are copied only on request.
 * All methods reject operation reentry and foreign threads; close is idempotent and terminal host cleanup closes automatically.
 */
@InternalStrataRuntimeApi
public interface UiRenderMonitor : AutoCloseable {
    /**
     * Clears interval counters and retired-node records while preserving live identities.
     */
    public fun checkpoint()

    /**
     * Copies detached, unmodifiable interval evidence without evaluating or modifying the screen.
     * Terminal failure releases runtime references but leaves a final detached snapshot readable until explicit close.
     */
    public fun snapshot(): UiRenderSnapshot

    /**
     * Finds current component identities; the same sibling key may match several independent parents.
     */
    public fun findNodes(key: ElementKey<*>): List<UiRenderNodeId>

    /**
     * Releases all recorded references and disconnects monitoring without closing the screen.
     */
    override fun close()
}
