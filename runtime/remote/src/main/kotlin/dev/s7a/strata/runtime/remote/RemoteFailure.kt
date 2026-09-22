package dev.s7a.strata.runtime.remote

/**
 * Detached terminal reasons understood without exposing server exception messages or stack traces.
 */
public enum class RemoteFailure {
    UnsupportedProtocol,
    UnsupportedType,
    InvalidMessage,
    ResourceLimit,
    TimedOut,
    Disconnected,
    PeerClosed,
    OwnerClosed,
    Replaced,
    HandlerFailed,
    ContainerChanged,
}
