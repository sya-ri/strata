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
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val requested = System.getProperty("strata.performance.workloads")
        val methods = if (sessions.not() && requested != null) JmhFixtureSelection.methods(JmhFixtureSelection.all(), requested) else null
        val fixtures =
            when {
                sessions -> listOf(RemoteSessionBenchmark::class.java)
                methods != null -> JmhFixtureSelection.all().filter { fixture -> methods.any { it.substringBeforeLast('.') == fixture.name.replace('$', '.') } }
                else -> listOf(RemoteProtocolBenchmark::class.java)
            }
        JmhFixtureSelection.verifyWork(fixtures)
        val parameters =
            if (sessions) {
                val selection = PerformanceSelection(RemoteSessionWorkload.entries.map { it.name }.toSet(), requested)
                mapOf("workload" to if (smoke) selection.ids.take(1).toSet() else selection.ids)
            } else if (smoke) {
                mapOf("nodes" to setOf("100"), "change" to setOf(RemoteChange.Single.name))
            } else {
                emptyMap()
            }
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val includes =
            if (methods == null) {
                listOf(args[2])
            } else {
                methods.map { "^${Regex.escape(it)}$" }
            }
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        val options = includes + args.drop(3) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            fixtures,
            mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree"),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI())),
        )
    }
}
