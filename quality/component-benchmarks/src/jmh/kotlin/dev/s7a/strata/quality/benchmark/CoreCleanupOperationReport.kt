package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import java.nio.file.Path

/**
 * Sixteen complete retained owner close or removal operations with their original fresh trees and graphs.
 * Required terminal close remains inside the operation; retirement and suppression assertions remain outside sampling.
 */
public object CoreCleanupOperationReport : JvmOperationReportAdapter {
    private enum class Boundary(
        val method: String,
        val remove: Boolean,
    ) {
        Close("closeTree", false),
        Remove("reconcileRemoval", true),
    }

    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    override val rows: List<JvmOperationReportRow> =
        Boundary.entries.flatMap { boundary ->
            CoreCleanupBenchmark.Workload.entries.map { workload ->
                JvmOperationReportRow(
                    "${boundary.method}-${workload.name}",
                    "${CoreCleanupBenchmark::class.java.name}.${boundary.method}",
                    mapOf("workload" to workload.name),
                    "Construct and attach a fresh retained tree, perform its complete cleanup and preserve the original throwable graph.",
                )
            }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        Boundary.entries
            .flatMap { boundary ->
                CoreCleanupBenchmark.Workload.entries.map { workload ->
                    val id = "${boundary.method}-${workload.name}"
                    val state = CoreCleanupBenchmark.Cleanup().apply { this.workload = workload }
                    val report =
                        JvmPerformanceRunner.measure(
                            id,
                            plan,
                            afterOperation = { _, _ -> state.verify() },
                        ) {
                            state.execute(boundary.remove) ?: Unit
                        }
                    id to report.evidence
                }
            }.toMap()
}
