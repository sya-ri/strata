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
    ;

    /**
     * Keeps the measured server archive registration identical to the offline verifier.
     */
    companion object {
        /**
         * Actual host configuration files required in the caller's kit input manifest.
         */
        val inputLabels: Set<String> = setOf("server-properties", "global-configuration", "world-defaults")

        /**
         * Exact representative ownership shared by collection and archive verification.
         */
        val representatives: Map<String, String> =
            mapOf(
                "host-api" to "org.bukkit.Bukkit",
                "paper-api" to "dev.s7a.strata.paper.Strata",
                "paper-runtime" to "dev.s7a.strata.runtime.paper.PaperScreens",
                "api" to "dev.s7a.strata.ui.UiDefinition",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "remote" to "dev.s7a.strata.runtime.remote.RemoteServerSession",
            )
    }
}
