package dev.s7a.strata.integration.velocity

/**
 * Queue submission is separate from closing acknowledged native HUD sessions on the proxy's UI owner.
 */
internal enum class VelocityPerformanceWorkload(
    val interval: String,
    val hudCount: Int = 0,
) {
    OwnerEntrySubmission("owner-entry-submission"),
    CapabilitiesSubmission("capabilities-submission"),
    HudClose("hud-close-100-nodes", 1),
    HudPairClose("hud-pair-close-200-nodes", 2),
    ;

    /**
     * Keeps the measured proxy archive registration identical to the offline verifier.
     */
    companion object {
        /**
         * Actual proxy and backend configurations required in the caller's kit input manifest.
         */
        val inputLabels: Set<String> = setOf("proxy-configuration", "first-backend", "second-backend")

        /**
         * Exact representative ownership shared by collection and archive verification.
         */
        val representatives: Map<String, String> =
            mapOf(
                "host-api" to "com.velocitypowered.api.proxy.ProxyServer",
                "velocity-api" to "dev.s7a.strata.velocity.VelocityUi",
                "velocity-runtime" to "dev.s7a.strata.runtime.velocity.VelocityScreens",
                "api" to "dev.s7a.strata.ui.UiDefinition",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "remote" to "dev.s7a.strata.runtime.remote.RemoteServerSession",
            )
    }
}
