package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.ControlCase
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.ControlOperation
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.LayoutCase
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.LayoutOperation
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Measures all 60 original current-line construction opportunities and 22 controls with the shared owner-thread meter.
 * Original scene setup and independent scalar, pixel, old-frame and release oracles remain outside samples.
 * Each sample calls the original benchmark method once, including its complete public state/input/frame cycle.
 * Surviving scene cleanup remains outside samples and must succeed before a complete report is returned.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object TextLineLayoutOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    override val rows: List<JvmOperationReportRow> =
        LayoutCase.entries.map(::layoutRow) + ControlCase.entries.map(::controlRow)

    /**
     * Requires every original generated method/parameter combination in both modes, including all 22 controls.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(TextLineLayoutBenchmark::class.java), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        check(rows.size == 82 && rows.map { it.id }.distinct().size == 82)
        check(actual.size == 164 && actual.toSet() == expected)
        check(rows.count { it.control } == 22 && rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        val benchmark = TextLineLayoutBenchmark()
        val reports = linkedMapOf<String, JsonObject>()
        LayoutCase.entries.forEach { workload ->
            val scene = TextLineLayoutBenchmark.LayoutScene().apply { this.workload = workload }
            AutoCloseable { scene.close() }.use { _ ->
                scene.setUp()
                reports.putAll(measure(layoutRow(workload), workload.consumer, plan) { benchmark.layout(scene) })
            }
        }
        ControlCase.entries.forEach { workload ->
            val scene = TextLineLayoutBenchmark.ControlScene().apply { this.workload = workload }
            AutoCloseable { scene.close() }.use { _ ->
                scene.setUp()
                reports.putAll(measure(controlRow(workload), workload.consumer, plan, workload.operation === ControlOperation.Clean) { benchmark.control(scene) })
            }
        }
        check(reports.keys == rows.map { it.id }.toSet())
        return reports
    }

    private fun measure(
        row: JvmOperationReportRow,
        consumer: Consumer,
        plan: PerformancePlan,
        clean: Boolean = false,
        operation: () -> RuntimeUiFrame,
    ): Map<String, JsonObject> {
        val role = if (consumer === Consumer.TextArea) SemanticsRole.TextArea else SemanticsRole.Text
        val expected = if (clean) operation() else null
        lateinit var last: RuntimeUiFrame
        JvmPerformanceRunner.warmup(plan.warmup) { last = operation() }
        return JvmPerformanceRunner.sequence(plan.samples, mapOf(row.id to plan.samples)) { _, sequence ->
            sequence.sample(row.id) { last = operation() }
            check(last.semantics.count { entry -> entry.semantics.role === role } == 1)
            if (clean) check(last === expected)
        }
    }

    private fun layoutRow(workload: LayoutCase): JvmOperationReportRow =
        JvmOperationReportRow(
            "layout-${workload.name}",
            "${TextLineLayoutBenchmark::class.java.name}.layout",
            mapOf("workload" to workload.name),
            when (workload.operation) {
                LayoutOperation.Initial -> "Replace one keyed retained component, then settle its complete frame using the prepared host and font owner."
                LayoutOperation.Edit -> "Apply one scalar-safe prepared content change to the retained owner, then settle its complete frame."
                LayoutOperation.Reflow -> "Switch the prepared viewport width and settle one complete reflow frame on the retained owner."
            },
        )

    private fun controlRow(workload: ControlCase): JvmOperationReportRow =
        JvmOperationReportRow(
            "control-${workload.name}",
            "${TextLineLayoutBenchmark::class.java.name}.control",
            mapOf("workload" to workload.name),
            when (workload.operation) {
                ControlOperation.Clean -> "Return one unchanged frame from the primed retained owner."
                ControlOperation.Caret -> "Dispatch the prepared right and left scalar moves, settling a frame after each to restore the original opportunity."
                ControlOperation.Composition -> "Apply the prepared preedit, focused-range update and composition loss, settling a frame after each complete public input."
            },
            control = true,
        )
}
