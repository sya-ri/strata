package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.SmallSemanticsBenchmark.Mode
import dev.s7a.strata.quality.benchmark.SmallSemanticsBenchmark.Scene
import dev.s7a.strata.quality.benchmark.SmallSemanticsBenchmark.Workload
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Reports every original generated collection row, including many-emission and complete clean-frame controls.
 * Each row preserves ordinary or captured callback scopes and the original session operation boundary.
 * Complete verification, construction-state observations, preparation and terminal cleanup remain untimed.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object SmallSemanticsOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    override val rows: List<JvmOperationReportRow> =
        Workload.entries.flatMap { workload -> listOf(false, true).map { captured -> row(workload, captured) } }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = SmallSemanticsBenchmark()
        val reports = linkedMapOf<String, JsonObject>()
        for (workload in Workload.entries) {
            for (captured in listOf(false, true)) {
                val row = row(workload, captured)
                val scene =
                    Scene().apply {
                        this.workload = workload
                        this.captured = captured
                    }
                try {
                    scene.setUp()
                    val sample =
                        JvmPerformanceRunner.measure<RuntimeUiFrame>(row.id, plan) {
                            benchmark.collect(scene)
                        }
                    check(reports.put(row.id, sample.evidence) == null)
                } finally {
                    scene.close()
                }
            }
        }
        return reports
    }

    private fun row(
        workload: Workload,
        captured: Boolean,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "collect-${workload.name}-$captured",
            "${SmallSemanticsBenchmark::class.java.name}.collect",
            mapOf("workload" to workload.name, "captured" to captured.toString()),
            "Complete the original session frame with its selected invalidation, reconciliation or initial lifecycle boundary and declared scope escape.",
            control = workload == Workload.RefreshMany128x8 || workload.mode == Mode.Clean,
        )
}
