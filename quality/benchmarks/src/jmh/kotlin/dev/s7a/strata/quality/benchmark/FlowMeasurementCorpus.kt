package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory

/**
 * Immutable complete generated metadata inventory, including all unchanged reactive controls.
 */
internal object FlowMeasurementCorpus {
    /**
     * Rejects missing, duplicated, changed or unexpected identities before either runtime is admitted.
     */
    fun verifyInventory() {
        val expected =
            checkNotNull(javaClass.getResourceAsStream("/flow-measurement-v1.jsonl"))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readLines() }
        check(expected.size == 176 && expected.distinct().size == expected.size)
        val actual =
            JmhWorkloadInventory.capture(
                listOf(FlowMeasurementBenchmark::class.java, ReactiveRenderingBenchmark::class.java),
                setOf("avgt", "sample"),
            )
        check(actual == expected.toSet()) { "The complete FlowRow/unchanged-reactive generated matrix changed" }
        check(FlowMeasurementCase.Topology.entries.size == 14)
        check(FlowMeasurementCase.entries.size == 70)
    }
}
