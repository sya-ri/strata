package dev.s7a.strata.runtime.remote

/**
 * Observable local state of a remote screen's ownership lifetime.
 */
public sealed interface RemoteSessionStatus {
    /**
     * Declaration ownership has not yet been successfully presented to the peer.
     */
    public data object Opening : RemoteSessionStatus

    /**
     * An initial snapshot has been sent or accepted.
     */
    public data object Open : RemoteSessionStatus

    /**
     * All owned references have been released; the reason is safe to send to the peer.
     */
    public data class Closed(
        public val reason: RemoteFailure,
    ) : RemoteSessionStatus
}
