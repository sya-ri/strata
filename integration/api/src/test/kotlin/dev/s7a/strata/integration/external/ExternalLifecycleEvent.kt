package dev.s7a.strata.integration.external

/**
 * Typed lifecycle observations emitted by the external integration fixture.
 */
internal sealed interface ExternalLifecycleEvent {
    /**
     * The node acquired its attached resources.
     *
     * @property id the typed node identity.
     */
    data class Attach(
        val id: ExternalNodeId,
    ) : ExternalLifecycleEvent

    /**
     * The node released its tree attachment.
     *
     * @property id the typed node identity.
     */
    data class Detach(
        val id: ExternalNodeId,
    ) : ExternalLifecycleEvent

    /**
     * The node released its owned resources permanently.
     *
     * @property id the typed node identity.
     */
    data class Dispose(
        val id: ExternalNodeId,
    ) : ExternalLifecycleEvent
}
