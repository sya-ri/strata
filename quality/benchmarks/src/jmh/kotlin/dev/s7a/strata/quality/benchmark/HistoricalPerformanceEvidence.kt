package dev.s7a.strata.quality.benchmark

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
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val fixtures = if (smoke) listOf(RenderingBenchmark::class.java) else fixtures()
        val parameters = if (smoke) mapOf("viewport" to setOf(RenderingBenchmark.Viewport.Compact.name)) else emptyMap()
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, listOf(args[2]))
        JmhPerformanceRunner.run(
            (args.drop(2) + if (smoke) listOf("-p", "viewport=${RenderingBenchmark.Viewport.Compact.name}") else emptyList()).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.component.UiScope",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))),
        )
    }

    /**
     * Exact historical fixture class registration shared by collection and the untimed completeness check.
     */
    public fun fixtures(): List<Class<*>> = listOf(RenderingBenchmark::class.java, ReactiveRenderingBenchmark::class.java, OverlayRenderingBenchmark::class.java)
}
