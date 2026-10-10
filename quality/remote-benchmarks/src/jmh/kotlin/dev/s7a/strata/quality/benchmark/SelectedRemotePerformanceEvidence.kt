package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Generated remote fixture registration through the existing shared selector and JMH collector.
 */
public object SelectedRemotePerformanceEvidence {
    /**
     * Accepts a fresh output directory, independent repetition and unchanged standard JMH CLI options.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        require(System.getProperty("strata.performance.smoke", "false").toBooleanStrict().not()) { "Smoke and explicit fixture selection are separate scopes" }
        RemoteWorkEvidence.verifySurface()
        val registered = JmhFixtureSelection.select(listOf(RemoteProtocolBenchmark::class.java))
        val methods = JmhFixtureSelection.methods(registered)
        val fixtures = registered.filter { fixture -> methods.any { it.substringBeforeLast('.') == fixture.name.replace('$', '.') } }
        val includes = methods.map { "^" + Regex.escape(it) + "$" }
        val parameters = JmhFixtureSelection.parameters()
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        JmhFixtureSelection.verifyWork(fixtures)
        val requiredInputs =
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
                mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI()))
        val additionalInputs = JmhFixtureSelection.inputs()
        require(requiredInputs.keys.intersect(additionalInputs.keys).isEmpty()) { "Fixture input labels overlap required control inputs" }
        val options = includes + args.drop(3) + parameters.flatMap { (name, values) -> listOf("-p", name + "=" + values.sorted().joinToString(",")) }
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.projection.ProjectionValue",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "remote" to "dev.s7a.strata.runtime.remote.RemoteTree",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            requiredInputs + additionalInputs,
        )
    }
}
