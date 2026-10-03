package dev.s7a.strata.quality.benchmark

/**
 * Fixed per-session and aggregate inputs, checked against the current real protocol limits before collection.
 */
internal enum class RemoteSessionWorkload(
    val sessions: Int,
    val nodes: Int,
) {
    Single100(1, 100),
    Single8192(1, 8192),
    Shared16At512(16, 512),
}
