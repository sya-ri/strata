package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonParser
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.PerformanceSelection
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

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
        val parameterFile = System.getProperty("strata.performance.parameters")?.let(Path::of)
        require(smoke.not() || (requested == null && parameterFile == null)) { "Smoke and targeted collection are separate scopes" }
        val registered = fixtures()
        val methods = JmhWorkloadInventory.capture(registered, setOf("avgt")).map { JsonParser.parseString(it).asJsonArray[0].asString }.toSet()
        val selection = PerformanceSelection(methods.map { it.substringAfter("dev.s7a.strata.quality.benchmark.") }.toSet(), requested)
        val fixtures = if (smoke) listOf(RenderingBenchmark::class.java) else registered.filter { fixture -> selection.ids.any { it.startsWith("${fixture.simpleName}.") } }
        val parameters =
            if (smoke) {
                mapOf("viewport" to setOf(RenderingBenchmark.Viewport.Compact.name))
            } else {
                parameterFile
                    ?.let { file ->
                        val properties = Properties().apply { Files.newBufferedReader(file, Charsets.UTF_8).use(::load) }
                        properties.stringPropertyNames().associateWith { name ->
                            val values = properties.getProperty(name).split(',').map(String::trim)
                            require(values.all(String::isNotBlank) && values.distinct().size == values.size) { "Empty or duplicate JMH parameter selection" }
                            values.toSet()
                        }
                    }.orEmpty()
            }
        val includes = if (requested == null) listOf(args[2]) else selection.ids.map { Regex.escape("dev.s7a.strata.quality.benchmark.$it") }
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
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
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI())),
        )
    }

    /**
     * Exact historical fixture class registration shared by collection and the untimed completeness check.
     */
    public fun fixtures(): List<Class<*>> = listOf(RenderingBenchmark::class.java, ReactiveRenderingBenchmark::class.java, OverlayRenderingBenchmark::class.java)
}
