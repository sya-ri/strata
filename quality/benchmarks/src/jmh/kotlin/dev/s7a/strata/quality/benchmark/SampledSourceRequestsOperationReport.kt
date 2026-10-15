package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan

/**
 * Binds the complete original preparation and borrowing methods to owner-thread counters.
 * Immutable commands, priming, deterministic verification and terminal device release stay outside sampling.
 * Resources use the original signalled native-free driver; these counters provide no native upload or GPU evidence.
 */
public object SampledSourceRequestsOperationReport : JvmOperationReportAdapter {
    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Prepare("prepare", "Partition every original ordered command and construct one complete prepared frame."),
        Borrow("borrow", "Borrow the primed frame requests, resolve live capacity and accounting, then close the detached lookup."),
    }

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation ->
            SampledSourceRequestsBenchmark.Case.entries.map { case -> row(operation, case) }
        }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = SampledSourceRequestsBenchmark()
        val reports = linkedMapOf<String, JsonObject>()
        for (operation in Operation.entries) {
            for (case in SampledSourceRequestsBenchmark.Case.entries) {
                val row = row(operation, case)
                val state = SampledSourceRequestsBenchmark.SourceState().apply { this.case = case }
                try {
                    state.setup()
                    val sample =
                        JvmPerformanceRunner.measure<Any>(row.id, plan) {
                            when (operation) {
                                Operation.Prepare -> benchmark.prepare(state)
                                Operation.Borrow -> benchmark.borrow(state)
                            }
                        }
                    check(reports.put(row.id, sample.evidence) == null)
                } finally {
                    state.close()
                }
            }
        }
        return reports
    }

    private fun row(
        operation: Operation,
        case: SampledSourceRequestsBenchmark.Case,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-${case.name}",
            "${SampledSourceRequestsBenchmark::class.java.name}.${operation.method}",
            mapOf("case" to case.name),
            operation.boundary,
            control = case.occurrences == case.identities,
        )
}
