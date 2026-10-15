package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.SamplingCompositionBenchmark.Case
import dev.s7a.strata.quality.benchmark.SamplingCompositionBenchmark.ProofState

/**
 * Runs every original proof and partition boundary through the shared owner-thread meter.
 * Immutable commands, conservative oracle checks and state setup remain outside sampling.
 * The separate untimed work diagnostic retains actual admission and charged-work counters.
 */
public object SamplingCompositionOperationReport : JvmOperationReportAdapter {
    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Proof("proof", "Construct one proof and query every original mask under the shared complete-frame budget."),
        Partition("partition", "Partition the complete original ordered frame with its conservative composition proof."),
    }

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation -> Case.entries.map { case -> row(operation, case) } }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = SamplingCompositionBenchmark()
        return Operation.entries.flatMap { operation ->
            Case.entries.map { case ->
                val row = row(operation, case)
                val state = ProofState().apply { this.case = case }
                state.setup()
                val sample =
                    JvmPerformanceRunner.measure<Any>(row.id, plan) {
                        when (operation) {
                            Operation.Proof -> benchmark.proof(state)
                            Operation.Partition -> benchmark.partition(state)
                        }
                    }
                row.id to sample.evidence
            }
        }.toMap()
    }

    private fun row(
        operation: Operation,
        case: Case,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-${case.name}",
            "${SamplingCompositionBenchmark::class.java.name}.${operation.method}",
            mapOf("case" to case.name),
            operation.boundary,
            control = case in setOf(Case.OrdinaryLarge, Case.OverlappingMany, Case.RepeatedMany, Case.EarlyBlockedMany, Case.LateBlockedMany),
        )
}
