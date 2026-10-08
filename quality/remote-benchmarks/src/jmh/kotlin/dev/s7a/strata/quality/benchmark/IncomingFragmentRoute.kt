package dev.s7a.strata.quality.benchmark

/**
 * Production follows the actual loaded runtime's privileged route when present.
 * Public is the unchanged defensive decode/Frame-offer control on both revisions.
 */
public enum class IncomingFragmentRoute {
    Production,
    Public,
}
