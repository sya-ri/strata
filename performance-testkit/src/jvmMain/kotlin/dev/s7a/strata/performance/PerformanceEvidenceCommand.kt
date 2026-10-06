package dev.s7a.strata.performance

/**
 * Exact JVM evidence request commands, decoded once at the JSON boundary.
 */
internal enum class PerformanceEvidenceCommand(
    val label: String,
) {
    JmhSummary("jmh-summary"),
    JmhComparison("jmh-comparison"),
    ;

    /**
     * Rejects unknown requests without falling back to a weaker verification path.
     */
    internal companion object {
        /**
         * Resolves one supported external command without a default fallback.
         */
        internal fun decode(value: String): PerformanceEvidenceCommand = entries.single { it.label.contentEquals(value) }
    }
}
