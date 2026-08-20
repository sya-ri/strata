package dev.s7a.strata.integration.docs

/**
 * The two task-owned staging directories accepted by the showcase launchers.
 */
internal enum class ShowcaseStagingKind(
    internal val directoryName: String,
) {
    /**
     * Freshness-check staging.
     */
    Check("check"),

    /**
     * Source-synchronizing generation staging.
     */
    Generate("generate"),
}
