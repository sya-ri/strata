package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Case
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.DisplayLineLayoutBenchmark.Operation
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Supplemental owner counters for all 52 original public display-layout opportunities.
 * The original trial preparation, independent verification and surviving-host close stay outside samples.
 * Changed rows retain both mutations and frames; initial rows retain their keyed replacement work.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object DisplayLineLayoutOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    private val displayControls = setOf(Case.UnwrappedLongInitial, Case.UnboundedLongInitial, Case.UnwrappedEdit, Case.UnboundedReflow, Case.EmptyZeroHeightInitial, Case.ShortLine1Initial)

    override val rows: List<JvmOperationReportRow> =
        Case.entries.map { workload ->
            JvmOperationReportRow(
                workload.name,
                "${DisplayLineLayoutBenchmark::class.java.name}.layout",
                mapOf("workload" to workload.name),
                "Execute the original public layout opportunity, including both restoring mutations and frames for changed rows or one unchanged frame for clean rows.",
                control = workload.operation === Operation.Clean || workload.consumer !== Consumer.Display || workload in displayControls,
            )
        }

    /**
     * Preserves every generated method/parameter row and all 18 clean, short, empty and full-layout controls.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(DisplayLineLayoutBenchmark::class.java), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        check(rows.size == 52 && rows.map { it.id }.distinct().size == 52)
        check(rows.count { it.control } == 18 && rows.all { it.operationsPerSample == 1 })
        check(actual.size == 104 && actual.toSet() == expected)
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        return rows.associate { row ->
            val workload = Case.valueOf(row.parameters.getValue("workload"))
            val scene = DisplayLineLayoutBenchmark.Scene().apply { this.workload = workload }
            AutoCloseable { scene.close() }.use { _ ->
                scene.setUp()
                var last = scene.operation()
                JvmPerformanceRunner.warmup(plan.warmup) { last = scene.operation() }
                val reports =
                    JvmPerformanceRunner.sequence(plan.samples, mapOf(row.id to plan.samples)) { _, sequence ->
                        sequence.sample(row.id) { last = scene.operation() }
                        check(last.size == workload.viewport)
                    }
                row.id to reports.getValue(row.id)
            }
        }
    }
}
