package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory

/** Frozen executable 128-case inventory, checked against generated metadata rather than annotation reflection. */
internal object LinearMeasurementCorpus {
    /** Rejects any missing, duplicate, changed, or unexpected case/mode/parameter row before collection. */
    fun verifyInventory() {
        val expected =
            checkNotNull(javaClass.getResourceAsStream("/linear-measurement-v1.jsonl"))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readLines() }
        check(expected.size == 256 && expected.distinct().size == expected.size)
        val actual =
            JmhWorkloadInventory.capture(
                listOf(LinearMeasurementBenchmark::class.java, ReactiveRenderingBenchmark::class.java),
                setOf("avgt", "sample"),
            )
        check(actual == expected.toSet()) { "The complete linear/unchanged-reactive generated matrix changed" }
        check(LinearMeasurementCase.Topology.entries.size == 30)
        check(LinearMeasurementCase.entries.size == 110)
    }
}
