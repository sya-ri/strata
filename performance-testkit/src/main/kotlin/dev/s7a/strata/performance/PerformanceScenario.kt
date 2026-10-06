package dev.s7a.strata.performance

/**
 * Declarative coverage identity; executable application operations remain owned by the consumer.
 *
 * @param id stable unique scenario identifier.
 * @param features supported feature identifiers exercised by this workload.
 * @param hosts actual supported execution boundaries.
 * @param phases executable operations for each supported host.
 * @param inputIdentity immutable fixture and operation contract identity.
 */
public class PerformanceScenario(
    public val id: String,
    features: Set<String>,
    hosts: Set<PerformanceHost>,
    phases: Set<PerformancePhase>,
    public val inputIdentity: String,
) {
    /**
     * Detached feature inventory.
     */
    public val features: Set<String> = features.toSet()

    /**
     * Detached supported-host inventory.
     */
    public val hosts: Set<PerformanceHost> = hosts.toSet()

    /**
     * Detached operation inventory.
     */
    public val phases: Set<PerformancePhase> = phases.toSet()

    init {
        require(id.isNotBlank() && inputIdentity.isNotBlank())
        require(this.features.isNotEmpty() && this.features.all(String::isNotBlank))
        require(this.hosts.isNotEmpty() && this.phases.isNotEmpty())
    }
}
