package dev.s7a.strata.runtime.velocity

/**
 * Separate callback CPU, unchanged full public decoding and actual asynchronous owner processing scopes.
 */
internal enum class NativeRoutingPhase {
    Callback,
    PublicDecode,
    OwnerProcessing,
}
