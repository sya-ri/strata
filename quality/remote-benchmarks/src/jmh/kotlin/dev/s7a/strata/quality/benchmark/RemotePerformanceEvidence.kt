package dev.s7a.strata.quality.benchmark

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
        val fixtures = if (sessions) listOf(RemoteSessionBenchmark::class.java) else listOf(RemoteProtocolBenchmark::class.java)
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val requested = System.getProperty("strata.performance.workloads")
        require(sessions || requested == null) { "Workload selection requires the retained remote corpus" }
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
        val options = args.drop(2) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            fixtures,
            mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI())),
        )
    }
}
