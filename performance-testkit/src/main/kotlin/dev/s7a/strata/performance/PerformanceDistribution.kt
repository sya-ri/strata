package dev.s7a.strata.performance

import kotlin.math.ceil

/**
 * Detached nearest-rank statistics for one complete interval; values are nanoseconds.
 *
 * @param samples number of successful observations.
 * @param total sum of the observations.
 * @param p50 median observation.
 * @param p95 ninety-fifth percentile.
 * @param p99 ninety-ninth percentile.
 * @param maximum largest observation.
 */
public data class PerformanceDistribution(
    public val samples: Int,
    public val total: Long,
    public val p50: Long,
    public val p95: Long,
    public val p99: Long,
    public val maximum: Long,
) {
    /**
     * Computes statistics without modifying or retaining the caller's storage.
     */
    public companion object {
        /**
         * Rejects an empty, incomplete, or negative interval instead of publishing zero timings.
         */
        public fun of(values: List<Long>): PerformanceDistribution {
            require(values.isNotEmpty() && values.all { 0 <= it }) { "Missing or negative performance samples" }
            val sorted = values.sorted()

            fun rank(fraction: Double): Long = sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)]
            val total =
                sorted.fold(0L) { sum, value ->
                    require(value <= Long.MAX_VALUE - sum) { "Performance duration total overflowed" }
                    sum + value
                }
            return PerformanceDistribution(sorted.size, total, rank(0.5), rank(0.95), rank(0.99), sorted.last())
        }
    }
}
