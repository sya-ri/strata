package dev.s7a.strata.performance

/**
 * Consumer-declared registration for a fixed report workload, independent of collection and comparison.
 * Variant report fields may change between runtime candidates, but must remain identical within a side.
 * Phase conditions must include sample counts and every fixture invariant required by that workload.
 */
public data class PerformanceReportContract(
    public val workloadId: String,
    public val phaseKeys: List<String>,
    public val phaseCount: Int,
    public val reportConditions: Set<String>,
    public val phaseConditions: Set<String>,
    public val variantReportFields: Set<String> = emptySet(),
    public val variantPhaseFields: Set<String> = emptySet(),
    public val repetitions: Int = 3,
    public val schemaVersion: Int = 1,
)
