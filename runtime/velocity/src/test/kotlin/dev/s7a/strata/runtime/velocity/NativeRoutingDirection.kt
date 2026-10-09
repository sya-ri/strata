package dev.s7a.strata.runtime.velocity

/**
 * Authenticated callback source and ordinary envelope destination for the actual routing matrix.
 */
internal enum class NativeRoutingDirection {
    ClientBackend,
    BackendClient,
    ClientProxy,
}
