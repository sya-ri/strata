package dev.s7a.strata.performance

/**
 * Complete synchronous action wall time and separate action-to-animation-frame latency.
 * Neither distribution represents browser CPU accounting or GPU completion.
 */
public data class BrowserPerformanceSample(
    public val operation: PerformanceDistribution,
    public val animationFrame: PerformanceDistribution,
)
