package dev.s7a.strata.integration.paper

/**
 * Real negotiated-player operations measured independently on the Paper primary owner.
 */
internal enum class PaperPerformanceWorkload(
    val interval: String,
) {
    OwnerEntry("owner-entry"),
    Capabilities("capabilities"),
    HudLifetime("hud-lifetime-100-nodes"),
}
