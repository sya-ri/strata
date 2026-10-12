package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan

/**
 * Measures one original clean-frame dispatch for every frozen workload using the shared owner-thread meter.
 * Scene preparation, independent work assertions and terminal disposal remain outside samples.
 * Every scene retains the same state transitions and callback work as its original JMH trial.
 */
public object FocusedInputOperationReport : JvmOperationReportAdapter {
    override val rows: List<JvmOperationReportRow> =
        FocusedInputBenchmark.Workload.entries.map { workload ->
            JvmOperationReportRow(
                workload.name,
                "${FocusedInputBenchmark::class.java.name}.dispatch",
                mapOf("workload" to workload.name),
                "One complete prebuilt input event through the primed retained session; frame preparation is excluded.",
                control = workload in setOf(FocusedInputBenchmark.Workload.NoFocusPress, FocusedInputBenchmark.Workload.NoFocusCharacter),
            )
        }

    /**
     * Requires the static hook and every declared row to match actual generated benchmark methods and parameters.
     */
    public fun verifyRows() {
        check(FocusedInputBenchmark.operationReport() === this)
        val expected = JmhWorkloadInventory.capture(listOf(FocusedInputBenchmark::class.java), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        check(actual.size == 64 && actual.toSet() == expected && rows.map { it.id }.distinct().size == 32)
        check(rows.count { it.control } == 2 && rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val scenes = linkedMapOf<String, FocusedInputBenchmark.Scene>()
        var last: InputResult = InputResult.Ignored
        return AutoCloseable { close(scenes.values) }.use { _ ->
            FocusedInputBenchmark.Workload.entries.forEach { workload ->
                val scene = FocusedInputBenchmark.Scene().apply { this.workload = workload }
                scene.setUp()
                scenes[workload.name] = scene
            }
            JvmPerformanceRunner.warmup(plan.warmup) { scenes.values.forEach { scene -> last = scene.dispatch() } }
            JvmPerformanceRunner.sequence(plan.samples, rows.associate { it.id to plan.samples }) { _, sequence ->
                scenes.forEach { (id, scene) ->
                    sequence.sample(id) { last = scene.dispatch() }
                    check(last === InputResult.Ignored || last === InputResult.Consumed)
                }
            }
        }
    }

    private fun close(scenes: Collection<FocusedInputBenchmark.Scene>) {
        var failure: Throwable? = null
        scenes.forEach { scene ->
            runCatching { scene.close() }.exceptionOrNull()?.let { cause ->
                val previous = failure
                if (previous == null) {
                    failure = cause
                } else if (previous !== cause) {
                    previous.addSuppressed(cause)
                }
            }
        }
        failure?.let { throw it }
    }
}
