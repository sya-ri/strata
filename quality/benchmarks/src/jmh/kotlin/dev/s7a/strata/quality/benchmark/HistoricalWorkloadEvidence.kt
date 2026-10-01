package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory

/**
 * Verifies the real generated historical matrix without running another timing harness.
 */
public object HistoricalWorkloadEvidence {
    /**
     * Requires all fixed fixtures and parameter combinations, plus a genuinely separate one-case smoke subset.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        val fixtures = HistoricalPerformanceEvidence.fixtures()
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt")).size == 54)
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
}
