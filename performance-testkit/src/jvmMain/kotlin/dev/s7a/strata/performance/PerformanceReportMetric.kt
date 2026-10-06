package dev.s7a.strata.performance

/**
 * Explicit metric projection from an existing report, with optional per-sample normalization.
 * Unavailable measurements remain unavailable; scale converts declared units without another timer.
 */
public data class PerformanceReportMetric(
    public val name: String,
    public val path: List<String>,
    public val divisorPath: List<String>? = null,
    public val scale: Double = 1.0,
)
