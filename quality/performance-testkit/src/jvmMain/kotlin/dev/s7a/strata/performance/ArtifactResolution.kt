package dev.s7a.strata.performance

/**
 * External provenance resolution values decoded at evidence verification boundaries.
 */
internal enum class ArtifactResolution(
    private val token: String,
) {
    Resolved("resolved"),
    Available("available"),
    Unavailable("unavailable"),
    ;

    /**
     * Strict external-value decoding; unknown statuses cannot satisfy preparation.
     */
    companion object {
        /**
         * Returns a known status or rejects an unrecognized evidence contract.
         */
        internal fun decode(token: String): ArtifactResolution = requireNotNull(entries.firstOrNull { it.token == token }) { "Unknown artifact resolution: $token" }
    }
}
