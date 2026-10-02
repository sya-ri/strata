package dev.s7a.strata.integration.performance

/**
 * Detached registration of one real host's exact synchronous intervals, archive representatives and external inputs.
 * This describes required evidence; it does not certify that the host or any API member has executed.
 */
public class ServerPerformanceContract(
    public val host: String,
    intervals: List<String>,
    representatives: Map<String, String>,
    inputLabels: Set<String>,
) {
    public val intervals: List<String> = intervals.toList()
    public val representatives: Map<String, String> = representatives.toMap()
    public val inputLabels: Set<String> = inputLabels.toSet()

    init {
        require(host.isNotBlank() && this.intervals.isNotEmpty() && this.intervals.distinct().size == this.intervals.size)
        require(this.intervals.all { it.matches(Regex("[a-z][a-z0-9-]*")) })
        require(this.representatives.isNotEmpty() && this.inputLabels.isNotEmpty())
    }
}
