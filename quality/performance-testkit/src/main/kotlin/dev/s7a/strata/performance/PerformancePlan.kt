package dev.s7a.strata.performance

/**
 * Reproducible execution counts, independent of application data and host implementation.
 *
 * @param warmup successful operations discarded before collection.
 * @param samples successful operations required in each interval.
 * @param repetitions independent executions required for complete evidence.
 * @param preparationTimeoutMillis upper bound for readiness, not a performance threshold.
 */
public data class PerformancePlan(
    public val warmup: Int = 30,
    public val samples: Int = 60,
    public val repetitions: Int = 3,
    public val preparationTimeoutMillis: Long = 120_000,
) {
    init {
        require(0 <= warmup && 0 < samples && 0 < repetitions && 0 < preparationTimeoutMillis)
        require(warmup <= Int.MAX_VALUE - samples) { "Warm-up and sample count overflow" }
    }
}
