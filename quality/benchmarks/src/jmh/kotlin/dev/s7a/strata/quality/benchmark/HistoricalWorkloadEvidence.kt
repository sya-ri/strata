package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonParser
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmApiInventory
import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceHost
import dev.s7a.strata.performance.PerformanceInventory
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.PerformanceScenario
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the real generated historical matrix without running another timing harness.
 */
public object HistoricalWorkloadEvidence {
    /**
     * Requires all fixed fixtures and parameter combinations, plus a genuinely separate one-case smoke subset.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size <= 1)
        if (args.isNotEmpty()) {
            val destination = Path.of(args.single()).toAbsolutePath().normalize()
            Files.createDirectories(checkNotNull(destination.parent))
            Files.writeString(destination, headlessSurface().getValue("headless").sorted().joinToString("\n", postfix = "\n"))
            return
        }
        verifySurface()
        val fixtures = HistoricalPerformanceEvidence.fixtures()
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt")).size == 54)
        check(JmhWorkloadInventory.capture(listOf(NonuniformOverlayBenchmark::class.java), setOf("avgt")).size == 6)
        check(JmhWorkloadInventory.capture(listOf(SampledRasterBenchmark::class.java), setOf("avgt")).size == 12)
        check(JmhWorkloadInventory.capture(listOf(DenseSampledRasterBenchmark::class.java), setOf("avgt")).size == 6)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt", "sample")).size == 108)
        check(
            JmhWorkloadInventory
                .capture(
                    listOf(RenderingBenchmark::class.java),
                    setOf("avgt"),
                    mapOf("viewport" to setOf(RenderingBenchmark.Viewport.Compact.name)),
                    listOf("RenderingBenchmark.cleanUiSessionFrame"),
                ).size == 1,
        )
        println("Verified all 54 historical AverageTime cases and the separate SampleTime/smoke registrations")
    }

    /**
     * Validates exact headless member registration against actual historical rasterization workloads.
     * This registers a module surface, rather than claiming that every member has executed.
     */
    public fun verifySurface() {
        val symbols = checkNotNull(javaClass.getResourceAsStream("/headless-api.tsv")).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        require(symbols.toSet().size == symbols.size) { "Duplicate headless performance API registration" }
        val feature = "Headless"
        val phases = mapOf("headlessRasterization" to PerformancePhase.Initial, "composition" to PerformancePhase.Update)
        val fixtures = listOf(RenderingBenchmark::class.java, OverlayRenderingBenchmark::class.java)
        val scenarios =
            JmhWorkloadInventory.capture(fixtures, setOf("avgt"), includes = listOf("(RenderingBenchmark.headlessRasterization|OverlayRenderingBenchmark.composition)")).map { identity ->
                val method =
                    JsonParser
                        .parseString(identity)
                        .asJsonArray[0]
                        .asString
                        .substringAfterLast('.')
                PerformanceScenario(identity, setOf(feature), setOf(PerformanceHost.Jvm), setOf(requireNotNull(phases[method])), "historical-headless-v1")
            }
        val surface = PerformanceInventory(headlessSurface(), mapOf("headless" to symbols.associateWith { feature }), mapOf("runtime/headless/src/" to setOf(feature)))
        val coverage = PerformanceCoverage(mapOf(feature to setOf(PerformanceHost.Jvm)), scenarios, mapOf(feature to phases.values.toSet()))
        coverage.selectChangedPaths(surface)
        println("Verified ${symbols.size} exact headless API symbols against ${scenarios.size} historical rasterization cases")
    }

    private fun headlessSurface(): Map<String, Set<String>> = JvmApiInventory.capture(javaClass.classLoader, mapOf("headless" to "dev.s7a.strata.runtime.headless.HeadlessImage"))
}
