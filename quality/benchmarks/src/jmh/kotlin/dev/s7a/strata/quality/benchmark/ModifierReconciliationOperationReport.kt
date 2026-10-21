package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.ModifierReconciliationBenchmark.Mutation
import dev.s7a.strata.quality.benchmark.ModifierReconciliationBenchmark.Scene
import dev.s7a.strata.quality.benchmark.ModifierReconciliationBenchmark.Workload
import dev.s7a.strata.runtime.UiTree
import java.nio.file.Path

/**
 * All 21 original alternating reconciliation rows, including changed-chain and empty-chain controls.
 * Each row owns one prepared tree; original verification, serialization and terminal close stay outside sampling.
 * Every sample performs one complete update with validation, component callbacks and child reconciliation.
 */
public object ModifierReconciliationOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    override val rows: List<JvmOperationReportRow> =
        Workload.entries.map { workload ->
            JvmOperationReportRow(
                "update-${workload.name}",
                "${ModifierReconciliationBenchmark::class.java.name}.update",
                mapOf("workload" to workload.name),
                "Alternate the frozen declaration and complete its retained modifier/component/child reconciliation.",
                control = workload.mutation != Mutation.Same || workload.modifierCount == 0,
            )
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = ModifierReconciliationBenchmark()
        return Workload.entries.zip(rows).associate { (workload, row) ->
            val scene = Scene().apply { this.workload = workload }
            try {
                scene.setUp()
                val sample =
                    JvmPerformanceRunner.measure<UiTree>(row.id, plan) {
                        benchmark.update(scene)
                    }
                row.id to sample.evidence
            } finally {
                scene.close()
            }
        }
    }
}
