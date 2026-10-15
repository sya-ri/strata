package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.TextFieldPreeditRow.Control
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Supplemental owner-thread counters for all original completed TextField preedit operations.
 * Fixture preparation and surviving-host cleanup remain outside samples; lifetime rows retain their original close inside next.
 * The shared kit owns sampling and counters, while the original JMH elapsed-time collector remains unchanged.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object TextFieldPreeditOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    override val rows: List<JvmOperationReportRow> =
        TextFieldPreeditRow.entries.map { row ->
            JvmOperationReportRow(
                row.name,
                "${TextFieldPreeditBenchmark::class.java.name}.completedFieldOperation",
                mapOf("row" to row.name),
                "Execute the original preconstructed input or appearance/control transition and its completed host frame, including terminal close for DisposeCloseRelease.",
                control = row.control != null,
            )
        }

    /**
     * Binds every row to both original JMH modes without introducing a smaller parameter subset.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(TextFieldPreeditBenchmark::class.java), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        check(rows.size == 78 && rows.map { it.id }.distinct().size == 78)
        check(rows.count { it.control } == 30 && rows.all { it.operationsPerSample == 1 })
        check(actual.size == 156 && actual.toSet() == expected)
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        return rows.associate { row ->
            val selected = TextFieldPreeditRow.valueOf(row.parameters.getValue("row"))
            TextFieldPreeditFixture(selected).use { fixture ->
                var last = fixture.frame()
                val width = if (selected.control == Control.WidthAndFontReplacement) 88 else 80
                JvmPerformanceRunner.warmup(plan.warmup) { last = fixture.next() }
                val reports =
                    JvmPerformanceRunner.sequence(plan.samples, mapOf(row.id to plan.samples)) { _, sequence ->
                        sequence.sample(row.id) { last = fixture.next() }
                        check(last.size.width == width && last.size.height == 20)
                    }
                row.id to reports.getValue(row.id)
            }
        }
    }
}
