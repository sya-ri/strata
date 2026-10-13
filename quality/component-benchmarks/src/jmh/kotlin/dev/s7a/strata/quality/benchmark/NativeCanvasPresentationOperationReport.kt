package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.NativeCanvasPresentationBenchmark.Scene
import dev.s7a.strata.quality.benchmark.NativeCanvasPresentationBenchmark.Workload
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * All 16 original CPU publication and preparation rows, including six Canvas-free controls.
 * Each row owns one original isolated device; setup, complete verification and terminal release remain outside sampling.
 * Driver callbacks perform CPU protocol work, so these reports contain no loaded-game GPU or upload measurements.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object NativeCanvasPresentationOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() =
            JmhFixtureSelection.inputs() +
                mapOf(
                    "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                    "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                    "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
                )

    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Publish("publishMembership", "Publish fixed completed command and receipt membership through the original constructor handle."),
        Prepare("prepareBatch", "Admit, map, validate, capture and cancel one original device batch; Canvas-free rows preserve the admission bypass."),
    }

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation -> Workload.entries.map { workload -> row(operation, workload) } }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = NativeCanvasPresentationBenchmark()
        val reports = linkedMapOf<String, JsonObject>()
        for (operation in Operation.entries) {
            for (workload in Workload.entries) {
                val row = row(operation, workload)
                val scene = Scene().apply { this.workload = workload }
                try {
                    scene.setUp()
                    val sample =
                        JvmPerformanceRunner.measure<Any>(row.id, plan) {
                            when (operation) {
                                Operation.Publish -> benchmark.publishMembership(scene)
                                Operation.Prepare -> benchmark.prepareBatch(scene)
                            }
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
        operation: Operation,
        workload: Workload,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-${workload.name}",
            "${NativeCanvasPresentationBenchmark::class.java.name}.${operation.method}",
            mapOf("workload" to workload.name),
            operation.boundary,
            control = workload.canvasCount == 0,
        )
}
