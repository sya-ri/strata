package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import java.nio.file.Path

/**
 * Twenty standalone collector controls through their original complete operation.
 * Fresh throwable construction remains inside sampling; complete graph checks remain outside it.
 */
public object CollectorCleanupOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    override val rows: List<JvmOperationReportRow> =
        CleanupFailuresBenchmark.Collector.values().flatMap { collector ->
            CleanupFailuresBenchmark.Workload.values().map { workload ->
                JvmOperationReportRow(
                    "collector-${collector.name}-${workload.name}",
                    "${CleanupFailuresBenchmark::class.java.name}.cleanup",
                    mapOf("collector" to collector.name, "workload" to workload.name),
                    "Construct the original accumulator and fresh graph, attempt every callback and return its final failure.",
                    control = true,
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        rows.associate { row ->
            val state = CleanupFailuresBenchmark.Cleanup()
            state.collector = CleanupFailuresBenchmark.Collector.valueOf(row.parameters.getValue("collector"))
            state.workload = CleanupFailuresBenchmark.Workload.valueOf(row.parameters.getValue("workload"))
            val report =
                JvmPerformanceRunner.measure(
                    row.id,
                    plan,
                    afterOperation = { _, _ -> state.verifyResult() },
                ) {
                    state.execute() ?: Unit
                }
            row.id to report.evidence
        }
}
