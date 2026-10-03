package dev.s7a.strata.performance

/**
 * JMH's serialized result modes; only SampleTime histograms establish per-operation latency quantiles.
 */
internal enum class JmhEvidenceMode(
    val label: String,
) {
    AverageTime("avgt"),
    SampleTime("sample"),
    Throughput("thrpt"),
    SingleShot("ss"),
    ;

    /**
     * Rejects unknown result modes before accepting their sample representation.
     */
    internal companion object {
        /**
         * Maps one serialized mode to its standard JMH sample representation.
         */
        internal fun decode(value: String): JmhEvidenceMode = entries.single { it.label.contentEquals(value) }
    }
}
