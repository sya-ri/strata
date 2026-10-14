package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.PendingSourceBenchmark.SourceScene
import dev.s7a.strata.quality.benchmark.PendingSourceBenchmark.Workload
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * All 22 original source-frame batches, including six single-root controls.
 * Each row owns one primed scene; source publication and every frame in its batch stay inside sampling.
 * Independent output and source-work assertions, diagnostics capture and terminal release remain outside samples.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object PendingSourceOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    private val controls = setOf(Workload.Idle1, Workload.One1, Workload.All1, Workload.EqualValue1, Workload.MapEqual1, Workload.MapChanged1)

    override val rows: List<JvmOperationReportRow> =
        Workload.entries.map { workload ->
            JvmOperationReportRow(
                workload.name,
                "${PendingSourceBenchmark::class.java.name}.sourceFrame",
                mapOf("workload" to workload.name),
                "Publish the original source update batch and complete all of its retained frames; equality publication includes the following cutoff and reset frame.",
                control = workload in controls,
            )
        }

    /**
     * Requires the adapter's complete matrix to equal generated JMH metadata in both original modes.
     */
    public fun verifyRows() {
        check(PendingSourceBenchmark.operationReport() === this)
        val expected = JmhWorkloadInventory.capture(listOf(PendingSourceBenchmark::class.java), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        check(actual.size == 44 && actual.toSet() == expected && rows.map { it.id }.distinct().size == 22)
        check(rows.count { it.control } == 6 && rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> =
        Workload.entries.associate { workload ->
            val scene = SourceScene().apply { this.workload = workload }
            try {
                scene.setUp()
                scene.verifyWork()
                val sample =
                    JvmPerformanceRunner.measure(
                        workload.name,
                        plan,
                        diagnosticsOwner = scene.diagnosticsOwner,
                        afterOperation = { _, frame -> scene.verifySample(frame) },
                    ) { scene.nextFrame() }
                workload.name to sample.evidence
            } finally {
                scene.close()
            }
        }
}
