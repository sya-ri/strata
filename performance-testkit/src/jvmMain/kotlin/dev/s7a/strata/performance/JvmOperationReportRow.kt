package dev.s7a.strata.performance

/**
 * Frozen logical operation identity and its complete original JMH method/parameter binding.
 * A sample contains [operationsPerSample] complete operations; counters normalize by samples,
 * so batching remains explicit rather than being presented as an individual operation.
 * [boundary] describes required steps and lifecycle work inside the sample.
 */
public data class JvmOperationReportRow(
    public val id: String,
    public val benchmark: String,
    public val parameters: Map<String, String>,
    public val boundary: String,
    public val operationsPerSample: Int = 1,
    public val control: Boolean = false,
)
