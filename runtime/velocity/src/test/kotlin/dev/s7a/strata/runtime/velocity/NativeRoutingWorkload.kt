package dev.s7a.strata.runtime.velocity

/**
 * Frozen complete-transfer sizes, native header controls and real callback routing conditions.
 */
internal enum class NativeRoutingWorkload {
    MinimumOpaque,
    SmallControl,
    Greeting,
    MaximumOpaque,
    MultiFragment,
    OneMiB,
    NearLimit,
    Cancellation,
    Discovery,
    Malformed,
    UnknownKind,
    UnknownEndpoint,
    StaleBackend,
    ProxyImpersonation,
    WrongChannel,
    AlreadyHandled,
    AbsentService,
    AbsentBackend,
    UnknownSender,
}
