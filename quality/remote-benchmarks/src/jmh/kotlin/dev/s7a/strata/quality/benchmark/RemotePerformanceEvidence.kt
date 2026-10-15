package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.PerformanceSelection
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
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        require(smoke.not() || selected.not()) { "Smoke and generated fixture selection are separate scopes" }
        val requested = System.getProperty("strata.performance.workloads")
        val methods = if (sessions.not() && selected.not() && requested != null) JmhFixtureSelection.methods(JmhFixtureSelection.all(), requested) else null
        val fixtures = selectFixtures(selected, sessions, methods)
        val parameters = selectParameters(selected, sessions, smoke, requested)
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val includes =
            if (selected) {
                if (requested == null) JmhFixtureSelection.includes(fixtures) else JmhFixtureSelection.methods(fixtures, requested).map { "^${Regex.escape(it)}$" }
            } else if (methods == null) {
                listOf(args[2])
            } else {
                methods.map { "^${Regex.escape(it)}$" }
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

    private fun selectFixtures(
        selected: Boolean,
        sessions: Boolean,
        methods: List<String>?,
    ): List<Class<*>> =
        when {
            selected -> JmhFixtureSelection.select(listOf(RemoteProtocolBenchmark::class.java))
            sessions -> listOf(RemoteSessionBenchmark::class.java)
            methods != null -> JmhFixtureSelection.all().filter { fixture -> methods.any { it.substringBeforeLast('.') == fixture.name.replace('$', '.') } }
            else -> listOf(RemoteProtocolBenchmark::class.java)
        }

    private fun selectParameters(
        selected: Boolean,
        sessions: Boolean,
        smoke: Boolean,
        requested: String?,
    ): Map<String, Set<String>> =
        when {
            selected -> {
                JmhFixtureSelection.parameters()
            }

            sessions -> {
                val selection = PerformanceSelection(RemoteSessionWorkload.entries.map { it.name }.toSet(), requested)
                mapOf("workload" to if (smoke) selection.ids.take(1).toSet() else selection.ids)
            }

            smoke -> {
                mapOf("nodes" to setOf("100"), "change" to setOf(RemoteChange.Single.name))
            }

            else -> {
                emptyMap()
            }
        }

    private fun inputs(): Map<String, Path> {
        val required = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI()))
        val fixtures = JmhFixtureSelection.inputs()
        require(required.keys.intersect(fixtures.keys).isEmpty()) { "Duplicate external fixture input labels" }
        return required + fixtures
    }
}
