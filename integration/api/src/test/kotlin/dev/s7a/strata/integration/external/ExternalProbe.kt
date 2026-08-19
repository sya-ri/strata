package dev.s7a.strata.integration.external

/**
 * Test-owned observation probe passed explicitly through an external element description.
 */
public class ExternalProbe {
    /**
     * Lifecycle events recorded by the probed node.
     */
    internal val lifecycle: MutableList<ExternalLifecycleEvent> = ArrayList()
}
