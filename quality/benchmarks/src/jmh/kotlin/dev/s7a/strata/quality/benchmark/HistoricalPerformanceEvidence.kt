package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Registers the unchanged historical fixtures with the shared JMH receipt adapter.
 * The original plugin task and its fixed inputs remain separate from these new collector-bound invocations.
 */
public object HistoricalPerformanceEvidence {
    /**
     * Accepts a fresh output directory, independent repetition index and the historical standard JMH CLI settings.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        HistoricalWorkloadEvidence.verifySurface()
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val requested = System.getProperty("strata.performance.workloads")
        require(smoke.not() || (requested == null && System.getProperty("strata.performance.parameters") == null && System.getProperty("strata.performance.benchmarks") == null)) { "Smoke and targeted collection are separate scopes" }
        val registered = JmhFixtureSelection.select(fixtures())
        JmhFixtureSelection.verifyWork(registered)
        val methods = JmhFixtureSelection.methods(registered, requested)
        val fixtures = if (smoke) listOf(RenderingBenchmark::class.java) else registered.filter { fixture -> methods.any { it.substringBeforeLast('.') == fixture.name.replace('$', '.') } }
        val parameters =
            if (smoke) {
                mapOf("viewport" to setOf(RenderingBenchmark.Viewport.Compact.name))
            } else {
                JmhFixtureSelection.parameters()
            }
        val includes =
            when {
                smoke -> listOf("^${Regex.escape(RenderingBenchmark::class.java.name)}\\.cleanUiSessionFrame$")
                requested == null -> JmhFixtureSelection.includes(fixtures)
                else -> methods.map { "^${Regex.escape(it)}$" }
            }
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        val inputs = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))
        val fixtureInputs = JmhFixtureSelection.inputs()
        require(inputs.keys.intersect(fixtureInputs.keys).isEmpty()) { "Duplicate external fixture input labels" }
        JmhPerformanceRunner.run(
            (includes + args.drop(3) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.component.UiScope",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            inputs + fixtureInputs,
        )
    }

    /**
     * Exact historical fixture class registration shared by collection and the untimed completeness check.
     */
    public fun fixtures(): List<Class<*>> = listOf(RenderingBenchmark::class.java, ReactiveRenderingBenchmark::class.java, OverlayRenderingBenchmark::class.java)
}
