package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.SamplingCoverageFixture.FrameOnly

/**
 * Measures the eight unchanged frame controls through their existing bounded reset/repeat transitions.
 * Every row primes one independent scene; the clean row borrows its current inputs and other rows replace only that current result.
 * Shared owner-thread CPU, allocation and wall measurements exclude trial setup and contain no native borrowing or presentation.
 */
public object SamplingCoverageFrameOperationReport : JvmOperationReportAdapter {
    override val rows: List<JvmOperationReportRow> =
        FrameOnly.entries.map { fixture ->
            JvmOperationReportRow(
                "${fixture.name}-CompleteCpuFramePreparation",
                "${SamplingCoverageFrameBenchmark::class.java.name}.completeCpuFramePreparation",
                mapOf("fixture" to fixture.name),
                "One original frame-control preparation or clean current-input borrow with its frozen repeat transition.",
                control = true,
            )
        }

    /**
     * Requires all original frame controls to match actual generated methods and parameters.
     */
    public fun verifyRows() {
        check(SamplingCoverageFrameBenchmark.operationReport() === this)
        val expected = JmhWorkloadInventory.capture(listOf(SamplingCoverageFrameBenchmark::class.java), setOf("avgt"))
        val actual = rows.map { JmhPerformanceRunner.workloadIdentity(it.benchmark, "avgt", it.parameters) }
        check(actual.size == 8 && actual.toSet() == expected && rows.map { it.id }.distinct().size == 8)
        check(rows.all { it.control && it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        SamplingCoverageWorkEvidence.verify(SamplingCoverageRuntime())
        val benchmark = SamplingCoverageFrameBenchmark()
        return FrameOnly.entries.zip(rows).associate { (fixture, row) ->
            val scene = SamplingCoverageFrameBenchmark.Scene().apply { this.fixture = fixture }
            scene.setup()
            val sample = JvmPerformanceRunner.measure<Any>(row.id, plan) { benchmark.completeCpuFramePreparation(scene) }
            row.id to sample.evidence
        }
    }
}
