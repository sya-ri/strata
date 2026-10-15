package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Case
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Operation
import java.nio.file.Path

/**
 * Reports owner-thread CPU, owner/all-thread allocation and wall time for all original 149 complete operations.
 * Prepared assets, independent oracles and surviving-owner cleanup remain outside each sample.
 * Lookup and slice samples retain their original 512-query and 32-slice batches without per-scalar normalization.
 */
public object TextProvenanceOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    override val rows: List<JvmOperationReportRow> =
        Case.entries.map { case ->
            JvmOperationReportRow(
                case.name,
                "${TextProvenanceBenchmark::class.java.name}.operate",
                mapOf("case" to case.name),
                when (case.operation) {
                    Operation.Lookup -> "Return the original folded 512-query trace over prepared scalar offsets."
                    Operation.Slice -> "Return all 32 original detached scalar-aligned slices."
                    else -> "Complete the original ${case.operation} operation, including its required frame restoration or ownership cycle."
                },
                control = case.operation === Operation.Clean,
            )
        }

    /**
     * Binds every row to the original complete AverageTime inventory and keeps one complete opportunity per sample.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(TextProvenanceBenchmark::class.java), setOf("avgt"))
        val actual = rows.map { row -> JmhPerformanceRunner.workloadIdentity(row.benchmark, "avgt", row.parameters) }
        check(rows.size == 149 && rows.map { it.id }.distinct().size == 149)
        check(actual.size == expected.size && actual.toSet() == expected)
        check(rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        return Case.entries.associate { case ->
            val report =
                TextProvenanceFixture(case).use { fixture ->
                    fixture.verify()
                    val measured = JvmPerformanceRunner.measure(case.name, plan) { fixture.perform() }
                    fixture.verify()
                    measured.evidence
                }
            case.name to report
        }
    }
}
