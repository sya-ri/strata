package dev.s7a.strata.performance

/**
 * Coverage gate and selection over the complete current feature inventory.
 *
 * @param inventory required feature-to-host combinations from the product contract.
 * @param scenarios detached registered workloads.
 * @param requiredPhases per-feature lifecycle obligations, independent of the scenarios that register them.
 */
public class PerformanceCoverage(
    inventory: Map<String, Set<PerformanceHost>>,
    scenarios: List<PerformanceScenario>,
    requiredPhases: Map<String, Set<PerformancePhase>> = inventory.keys.associateWith { setOf(PerformancePhase.Idle) },
) {
    private val inventory = inventory.mapValues { it.value.toSet() }
    private val scenarios = scenarios.toList()
    private val requiredPhases = requiredPhases.mapValues { it.value.toSet() }

    /**
     * Rejects duplicate, unknown, and missing feature/host combinations.
     */
    public fun verify() {
        require(inventory.isNotEmpty() && inventory.keys.all(String::isNotBlank))
        require(inventory.values.all { it.isNotEmpty() })
        require(requiredPhases.keys == inventory.keys && requiredPhases.values.all { it.isNotEmpty() })
        require(scenarios.map(PerformanceScenario::id).distinct().size == scenarios.size) { "Duplicate performance scenario" }
        scenarios.forEach { scenario ->
            scenario.features.forEach { feature ->
                require(feature in inventory) { "Unknown performance feature: $feature" }
                require(scenario.hosts.all { it in inventory.getValue(feature) }) { "Unsupported performance host: ${scenario.id}/$feature" }
            }
        }
        inventory.forEach { (feature, hosts) ->
            hosts.forEach { host ->
                requiredPhases.getValue(feature).forEach { phase ->
                    require(scenarios.any { feature in it.features && host in it.hosts && phase in it.phases }) { "Missing performance scenario: $feature/$host/$phase" }
                }
            }
        }
    }

    /**
     * Selects all workloads when impact is unknown, otherwise the exact affected feature union.
     */
    public fun select(changedFeatures: Set<String>? = null): List<PerformanceScenario> {
        verify()
        if (changedFeatures == null) return scenarios.toList()
        require(changedFeatures.all { it in inventory }) { "Unknown performance impact" }
        return scenarios.filter { scenario -> scenario.features.any { it in changedFeatures } }
    }

    /**
     * Validates the actual public surface against executable registrations and selects changed source owners.
     * Unknown paths select all workloads; an API/module addition without an assignment fails even for a narrow selection.
     */
    public fun selectChangedPaths(
        surface: PerformanceInventory,
        changedPaths: Set<String>? = null,
    ): List<PerformanceScenario> = select(surface.affectedFeatures(inventory.keys, changedPaths))

    /**
     * Requires every selected host/phase to produce a successful interval; skipped preparation is not evidence.
     */
    public fun verifyCompleted(
        completed: Set<Triple<String, PerformanceHost, PerformancePhase>>,
        changedFeatures: Set<String>? = null,
    ) {
        select(changedFeatures).forEach { scenario ->
            scenario.hosts.forEach { host ->
                scenario.phases.forEach { phase ->
                    require(Triple(scenario.id, host, phase) in completed) { "Missing performance interval: ${scenario.id}/$host/$phase" }
                }
            }
        }
    }

    /**
     * Requires complete independent repetitions with the registered fixture identity and no duplicate intervals.
     */
    public fun verifyEvidence(
        evidence: List<PerformanceEvidence>,
        plan: PerformancePlan = PerformancePlan(),
        changedFeatures: Set<String>? = null,
    ) {
        val selected = select(changedFeatures)
        val registered = selected.associateBy(PerformanceScenario::id)
        evidence.forEach { interval ->
            val scenario = requireNotNull(registered[interval.scenario]) { "Unknown performance evidence: ${interval.scenario}" }
            require(interval.host in scenario.hosts && interval.phase in scenario.phases)
            require(interval.inputIdentity == scenario.inputIdentity && interval.repetition in 0 until plan.repetitions)
        }
        val grouped = evidence.groupBy { Triple(it.scenario, it.host, it.phase) }
        selected.forEach { scenario ->
            scenario.hosts.forEach { host ->
                scenario.phases.forEach { phase ->
                    val records = grouped[Triple(scenario.id, host, phase)].orEmpty()
                    require(records.size == plan.repetitions && records.map(PerformanceEvidence::repetition).toSet() == (0 until plan.repetitions).toSet()) {
                        "Missing or duplicate performance repetitions: ${scenario.id}/$host/$phase"
                    }
                    records.drop(1).forEach { records.first().requireComparable(it) }
                    require(records.map(PerformanceEvidence::targetIdentity).distinct().size == 1) { "Measured runtime changed within a suite" }
                }
            }
        }
    }
}
