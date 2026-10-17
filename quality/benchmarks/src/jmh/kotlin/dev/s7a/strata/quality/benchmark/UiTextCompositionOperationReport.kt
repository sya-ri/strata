package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import java.nio.file.Path

/**
 * Reports all original text-composition rows as explicit 256-operation batches on one owner thread.
 * Input preparation, admission, assertions and report serialization stay outside each sample.
 */
public object UiTextCompositionOperationReport : JvmOperationReportAdapter {
    private val lengths = listOf(1, 2, 8, 32, 128)
    private const val BATCH = 256

    override val inputs: Map<String, Path>
        get() = JmhFixtureSelection.inputs() + mapOf("headless-api" to Path.of(checkNotNull(javaClass.getResource("/headless-api.tsv")).toURI()))

    override val rows: List<JvmOperationReportRow> =
        lengths.flatMap { length ->
            Operation.entries.map { operation ->
                row("${operation.method}-$length", operation.method, mapOf("length" to length.toString()), operation == Operation.ListControl)
            }
        } + listOf(row("empty", "empty", emptyMap(), true), row("singleton", "singleton", emptyMap(), true))

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val invocations =
            lengths.flatMap { length ->
                val state = UiTextCompositionBenchmark.Inputs()
                state.length = length
                state.setup()
                Operation.entries.map { operation -> { operation.invoke(state) } }
            } + UiTextCompositionBenchmark().let { benchmark -> listOf({ benchmark.empty() }, { benchmark.singleton() }) }
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
            "${UiTextCompositionBenchmark::class.java.name}.$method",
            parameters,
            "Repeat the original complete $method invocation 256 times, retaining the last result; declarations rebuild 128 labels per invocation.",
            operationsPerSample = BATCH,
            control = control,
        )

    /**
     * Typed complete-operation bindings preserve each original JMH method's work.
     */
    private enum class Operation(
        val method: String,
        val invoke: (UiTextCompositionBenchmark.Inputs) -> Any,
    ) {
        Constructor("constructor", UiTextCompositionBenchmark.Inputs::constructed),
        Companion("companion", UiTextCompositionBenchmark.Inputs::combined),
        ListControl("listControl", UiTextCompositionBenchmark.Inputs::listControl),
        Declarations("declarations", UiTextCompositionBenchmark.Inputs::declarations),
    }
}
