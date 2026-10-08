package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Whole remote TiledImage fixture registration using the existing shared JMH collector and provenance checks.
 */
public object TiledImageRemoteEvidence {
    /**
     * Accepts a fresh output directory, independent repetition and unchanged standard JMH options.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        val fixtures = listOf(TiledImageRemoteBenchmark::class.java)
        val includes = JmhFixtureSelection.includes(fixtures)
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), includes = includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        JmhFixtureSelection.verifyWork(fixtures)
        JmhPerformanceRunner.run(
            (includes + args.drop(3)).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.component.TiledImageSource",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "remote" to "dev.s7a.strata.runtime.remote.RemoteTree",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
                mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI())),
        )
    }
}
