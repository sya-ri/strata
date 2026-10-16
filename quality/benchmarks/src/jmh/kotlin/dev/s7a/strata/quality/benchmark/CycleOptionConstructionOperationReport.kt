package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import java.nio.file.Path

/**
 * Retains all 21 construction/rejection rows as explicit 256-call batches on their creating owner thread.
 */
public object CycleOptionConstructionOperationReport : JvmOperationReportAdapter {
    private const val BATCH = 256
    private val lengths = listOf(1, 2, 8, 32, 128)
    private val constructors = listOf("listFactory" to false, "directListControl" to true, "collectionControl" to true)
    private val independent = listOf("enumFactory" to false, "enumControl" to true, "emptyListRejected" to true, "emptyCollectionRejected" to true, "duplicateRejected" to true, "outsideInitialRejected" to true)

    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    override val rows: List<JvmOperationReportRow> =
        lengths.flatMap { length -> constructors.map { (method, control) -> row("$method-$length", method, mapOf("length" to length.toString()), control) } } +
            independent.map { (method, control) -> row(method, method, emptyMap(), control) }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val benchmark = CycleOptionConstructionBenchmark()
        val invocations: List<() -> Any> =
            lengths.flatMap { length ->
                val state = CycleOptionConstructionBenchmark.Inputs()
                state.length = length
                state.setup()
                listOf(state::listFactory, state::directListControl, state::collectionControl)
            } + listOf(benchmark::enumFactory, benchmark::enumControl, benchmark::emptyListRejected, benchmark::emptyCollectionRejected, benchmark::duplicateRejected, benchmark::outsideInitialRejected)
        check(invocations.size == rows.size)
        var last: Any? = null
        JvmPerformanceRunner.warmup(plan.warmup) {
            invocations.forEach { invoke -> repeat(BATCH) { last = invoke() } }
        }
        return JvmPerformanceRunner.sequence(plan.samples, rows.associate { it.id to plan.samples }) { _, sequence ->
            rows.forEachIndexed { index, row ->
                sequence.sample(row.id) { repeat(BATCH) { last = invocations[index]() } }
                checkNotNull(last)
            }
        }
    }

    private fun row(
        id: String,
        method: String,
        parameters: Map<String, String>,
        control: Boolean,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            id,
            "${CycleOptionConstructionBenchmark::class.java.name}.$method",
            parameters,
            "Construct or reject one complete public option state, including validation and observable creation where successful, 256 times; retain the final result.",
            operationsPerSample = BATCH,
            control = control,
        )
}
