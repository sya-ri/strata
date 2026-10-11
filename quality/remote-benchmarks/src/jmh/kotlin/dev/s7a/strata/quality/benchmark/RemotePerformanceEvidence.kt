package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.PerformanceSelection
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Consumer registration for the shared JMH collector, with no independent measurement or comparison engine.
 */
public object RemotePerformanceEvidence {
    /**
     * Accepts an independent output directory, repetition index and unchanged standard JMH CLI arguments.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        RemoteWorkEvidence.verifySurface()
        val sessions = System.getProperty("strata.performance.remoteSessions", "false").toBooleanStrict()
        val selected = System.getProperty("strata.performance.benchmarks") != null || System.getProperty("strata.performance.parameters") != null
        require(sessions.not() || selected.not()) { "Retained-session IDs and generated fixture selection are separate scopes" }
        val fixtures =
            if (selected) {
                JmhFixtureSelection.select(listOf(RemoteProtocolBenchmark::class.java))
            } else if (sessions) {
                listOf(RemoteSessionBenchmark::class.java)
            } else {
                listOf(RemoteProtocolBenchmark::class.java)
            }
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        require(smoke.not() || selected.not()) { "Smoke and generated fixture selection are separate scopes" }
        val requested = System.getProperty("strata.performance.workloads")
        val parameters =
            if (selected) {
                JmhFixtureSelection.parameters()
            } else if (sessions) {
                val selection = PerformanceSelection(RemoteSessionWorkload.entries.map { it.name }.toSet(), requested)
                mapOf("workload" to if (smoke) selection.ids.take(1).toSet() else selection.ids)
            } else if (smoke) {
                mapOf("nodes" to setOf("100"), "change" to setOf(RemoteChange.Single.name))
            } else {
                emptyMap()
            }
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val includes =
            if (selected) {
                if (requested == null) JmhFixtureSelection.includes(fixtures) else JmhFixtureSelection.methods(fixtures, requested).map { "^${Regex.escape(it)}$" }
            } else if (sessions || requested == null) {
                listOf(args[2])
            } else {
                val fixture = RemoteProtocolBenchmark::class.java
                val methods =
                    fixture.declaredMethods
                        .filter { it.isAnnotationPresent(Benchmark::class.java) }
                        .map { "${fixture.simpleName}.${it.name}" }
                        .sorted()
                        .toSet()
                PerformanceSelection(methods, requested).ids.map { Regex.escape("${fixture.packageName}.$it") }
            }
        val options = includes + args.drop(3) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }
        JmhFixtureSelection.verifyWork(fixtures)
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        val inputs = inputs()
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            fixtures,
            mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree"),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            inputs,
        )
    }

    private fun inputs(): Map<String, Path> {
        val required = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI()))
        val fixtures = JmhFixtureSelection.inputs()
        require(required.keys.intersect(fixtures.keys).isEmpty()) { "Duplicate external fixture input labels" }
        return required + fixtures
    }
}
