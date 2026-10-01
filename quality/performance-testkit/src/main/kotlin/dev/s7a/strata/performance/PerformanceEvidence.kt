package dev.s7a.strata.performance

/**
 * Immutable comparison contract; unavailable measurements remain null, never successful zero work.
 *
 * @param scenario stable workload identity.
 * @param host actual execution host.
 * @param phase measured operation.
 * @param repetition independent run index, starting at zero.
 * @param inputIdentity fixture and operation contract digest.
 * @param kitIdentity actual collector and adapter revision or artifact digest.
 * @param targetIdentity actual measured runtime digest.
 * @param conditions controlled host, Java/browser, viewport, scale, and workload settings.
 * @param metrics measured values, with units expressed in their names.
 */
public class PerformanceEvidence(
    public val scenario: String,
    public val host: PerformanceHost,
    public val phase: PerformancePhase,
    public val repetition: Int,
    public val inputIdentity: String,
    public val kitIdentity: String,
    public val targetIdentity: String,
    conditions: Map<String, String>,
    metrics: Map<String, Long?>,
) {
    /**
     * Detached controlled comparison settings.
     */
    public val conditions: Map<String, String> = conditions.toMap()

    /**
     * Detached measurements; null denotes unsupported or unavailable collection.
     */
    public val metrics: Map<String, Long?> = metrics.toMap()

    init {
        require(listOf(scenario, inputIdentity, kitIdentity, targetIdentity).all(String::isNotBlank))
        require(0 <= repetition && this.conditions.isNotEmpty() && this.metrics.isNotEmpty())
        require(this.metrics.all { it.key.isNotBlank() && (it.value?.let { value -> 0 <= value } ?: true) })
    }

    /**
     * Rejects cross-workload, cross-host, cross-environment, or cross-collector comparisons.
     */
    public fun requireComparable(candidate: PerformanceEvidence) {
        require(scenario == candidate.scenario && host == candidate.host && phase == candidate.phase)
        require(inputIdentity == candidate.inputIdentity && kitIdentity == candidate.kitIdentity && conditions == candidate.conditions) {
            "Performance comparison changed input, collector, or controlled conditions"
        }
        require(metrics.keys == candidate.metrics.keys && metrics.filterValues { it == null }.keys == candidate.metrics.filterValues { it == null }.keys) {
            "Performance comparison changed available metrics"
        }
    }
}
