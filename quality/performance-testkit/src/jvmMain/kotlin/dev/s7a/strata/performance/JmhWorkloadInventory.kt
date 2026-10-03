package dev.s7a.strata.performance

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.runner.BenchmarkList
import org.openjdk.jmh.runner.format.OutputFormatFactory
import org.openjdk.jmh.runner.options.VerboseMode

/**
 * Exact expected matrices from JMH's generated benchmark registry, without another annotation or timing engine.
 * Consumers register fixture classes, explicit standard modes and optional subsets of compiled parameter values.
 */
@Suppress("unused") // Public Maven API also launched by Gradle JMH source sets outside the IDEA call graph.
public object JmhWorkloadInventory {
    /**
     * Expands JMH-generated parameters and explicit mode overrides into complete workload identities.
     * Missing fixture metadata, unknown parameter overrides and unbounded matrices fail before measurement.
     * Generated metadata must be on the same runtime classpath as the actual JMH invocation.
     */
    public fun capture(
        fixtures: List<Class<*>>,
        modes: Set<String>,
        parameterOverrides: Map<String, Set<String>> = emptyMap(),
        includes: List<String> = emptyList(),
    ): Set<String> {
        val classes = fixtures.map { it.name }.toSet()
        require(classes.isNotEmpty() && classes.size == fixtures.size && modes.isNotEmpty())
        val selectedModes = modes.map(Mode::deepValueOf).toSet()
        require(Mode.All !in selectedModes) { "Register explicit JMH modes" }
        val output = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.SILENT)
        val entries = BenchmarkList.defaultList().find(output, includes.toList(), emptyList()).filter { it.userClassQName in classes }
        require(entries.map { it.userClassQName }.toSet() == classes) { "Missing generated JMH fixture metadata" }
        val methods =
            entries.groupBy { it.username }.mapValues { (_, variants) ->
                val parameters = variants.map { entry -> entry.params.orElse(emptyMap()).mapValues { it.value.toSet() } }
                require(parameters.all { it == parameters.first() }) { "Ambiguous generated JMH parameters" }
                parameters.first()
            }
        val known = methods.values.flatMap { it.keys }.toSet()
        require(parameterOverrides.keys.all { it in known } && parameterOverrides.values.all { it.isNotEmpty() }) { "Unknown or empty JMH parameter override" }
        return buildSet {
            methods.forEach { (method, declared) ->
                val parameters =
                    declared.mapValues { (name, values) ->
                        val selected = parameterOverrides[name] ?: values
                        require(selected.isNotEmpty() && selected.all { it in values }) { "Unregistered JMH parameter value: $name" }
                        selected
                    }
                combinations(parameters).forEach { combination ->
                    selectedModes.forEach { mode ->
                        require(size < 16_384) { "Oversized JMH workload inventory" }
                        add(JmhPerformanceRunner.workloadIdentity(method, mode.shortLabel(), combination))
                    }
                }
            }
        }
    }

    private fun combinations(parameters: Map<String, Set<String>>): List<Map<String, String>> =
        parameters.toSortedMap().entries.fold(listOf(emptyMap())) { combinations, (name, values) ->
            require(combinations.size.toLong() * values.size <= 16_384) { "Oversized JMH parameter matrix" }
            combinations.flatMap { combination -> values.sorted().map { value -> combination + (name to value) } }
        }
}
