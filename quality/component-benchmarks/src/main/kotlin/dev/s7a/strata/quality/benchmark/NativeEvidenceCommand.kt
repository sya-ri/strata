package dev.s7a.strata.quality.benchmark

/**
 * Native request operations decoded once while preserving existing summary requests.
 */
internal enum class NativeEvidenceCommand(
    private val label: String,
) {
    Summary("summary"),
    Comparison("comparison"),
    ;

    /**
     * Accepts a missing command only for the established summary request shape.
     */
    internal companion object {
        /**
         * Rejects unknown explicit operations without falling back to a weaker report.
         */
        internal fun decode(value: String?): NativeEvidenceCommand =
            if (value == null) {
                Summary
            } else {
                requireNotNull(entries.singleOrNull { it.label.contentEquals(value) }) { "Unknown native evidence command" }
            }
    }
}
