package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
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
        val fixtures = listOf(RemoteProtocolBenchmark::class.java)
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val parameters = if (smoke) mapOf("nodes" to setOf("100"), "change" to setOf(RemoteChange.Single.name)) else emptyMap()
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val options = args.drop(2) + if (smoke) listOf("-p", "nodes=100", "-p", "change=${RemoteChange.Single.name}") else emptyList()
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            fixtures,
            mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))),
        )
    }
}
