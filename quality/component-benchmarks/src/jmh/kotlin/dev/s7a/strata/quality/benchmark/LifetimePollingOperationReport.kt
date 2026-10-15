package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.NativeLifetimePollingBenchmark.Condition
import dev.s7a.strata.quality.benchmark.NativeLifetimePollingBenchmark.Subject
import java.nio.file.Path

/**
 * Binds every original native/portable or sampled lifetime boundary to the shared owner-thread CPU/allocation meter.
 * Persistent owners are prepared and primed before sampling; fresh retirement owns admission and close inside each sample.
 * Assertions, serialization and surviving-owner teardown stay outside samples, and unavailable counters remain unavailable.
 * [sampled] selects the actual Fabric fixture rather than the Canvas/portable fixture; it does not select a smaller corpus.
 */
public class LifetimePollingOperationReport(
    private val sampled: Boolean,
) : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Poll("poll", "Poll one persistent actual manager state, including the original retained-count read."),
        Submit("submit", "Submit every current resource, consume GUI use and settle the original reusable generation."),
        Retire("retireProtocol", "Construct, admit, retire, signal and close one fresh complete owner, including its original release assertions."),
    }

    private data class Case(
        val operation: Operation,
        val count: Int,
        val subject: Subject?,
        val condition: Condition?,
    )

    private val fixture = if (sampled) SampledLifetimePollingBenchmark::class.java else NativeLifetimePollingBenchmark::class.java
    private val cases =
        if (sampled) {
            listOf(0, 1, 256, 512).flatMap { count -> cases(count, null) }
        } else {
            Subject.entries.flatMap { subject -> listOf(0, 1, 64).flatMap { count -> cases(count, subject) } }
        }

    override val rows: List<JvmOperationReportRow> = cases.map(::row)

    /**
     * Rejects missing, duplicate or changed generated method/parameter bindings in both original JMH modes.
     * Empty inputs and settled polls remain explicitly identified controls across all original counts.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(fixture), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        val count = if (sampled) 36 else 81
        check(rows.size == count && rows.map { it.id }.distinct().size == count)
        check(actual.size == count * 2 && actual.toSet() == expected)
        check(rows.count { it.control } == if (sampled) 12 else 33)
        check(rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        val reports = linkedMapOf<String, JsonObject>()
        cases.forEach { case ->
            val report = if (sampled) sampledReport(case, plan) else nativeReport(case, plan)
            check(reports.put(row(case).id, report) == null)
        }
        check(reports.keys == rows.map { it.id }.toSet())
        return reports
    }

    private fun nativeReport(
        case: Case,
        plan: PerformancePlan,
    ): JsonObject {
        val subject = checkNotNull(case.subject)
        val id = row(case).id
        if (case.operation == Operation.Retire) {
            return JvmPerformanceRunner
                .measure(id, plan, afterOperation = { _, result -> check(result == 0) }) {
                    NativeLifetimePollingWorkload(subject, case.count, Condition.InitializationPending).use { workload -> workload.retireAndSignal() }
                }.evidence
        }
        return NativeLifetimePollingWorkload(subject, case.count, case.condition ?: Condition.Settled).use { workload ->
            if (case.operation == Operation.Submit) {
                workload.immediateCompletions()
                workload.submit()
                workload.verifyRetained()
            }
            val operation =
                when (case.operation) {
                    Operation.Poll -> workload::poll
                    Operation.Submit -> workload::submit
                    Operation.Retire -> error("Fresh retirement cannot reuse a persistent owner")
                }
            JvmPerformanceRunner.measure(id, plan, afterOperation = { _, _ -> workload.verifyRetained() }) { operation() }.evidence
        }
    }

    private fun sampledReport(
        case: Case,
        plan: PerformancePlan,
    ): JsonObject {
        val id = row(case).id
        if (case.operation == Operation.Retire) {
            return JvmPerformanceRunner
                .measure(id, plan, afterOperation = { _, result -> check(result == 0) }) {
                    SampledLifetimePollingWorkload(case.count, Condition.InitializationPending).use { workload -> workload.retireAndSignal() }
                }.evidence
        }
        return SampledLifetimePollingWorkload(case.count, case.condition ?: Condition.Settled).use { workload ->
            if (case.operation == Operation.Submit) {
                workload.immediateCompletions()
                workload.submit()
                workload.verifyRetained()
            }
            val operation =
                when (case.operation) {
                    Operation.Poll -> workload::poll
                    Operation.Submit -> workload::submit
                    Operation.Retire -> error("Fresh retirement cannot reuse a persistent owner")
                }
            JvmPerformanceRunner.measure(id, plan, afterOperation = { _, _ -> workload.verifyRetained() }) { operation() }.evidence
        }
    }

    private fun cases(
        count: Int,
        subject: Subject?,
    ): List<Case> =
        Condition.entries.map { condition -> Case(Operation.Poll, count, subject, condition) } +
            listOf(Case(Operation.Submit, count, subject, null), Case(Operation.Retire, count, subject, null))

    private fun row(case: Case): JvmOperationReportRow {
        val parameters = linkedMapOf("count" to case.count.toString())
        case.subject?.let { parameters["subject"] = it.name }
        case.condition?.let { parameters["condition"] = it.name }
        return JvmOperationReportRow(
            "${fixture.simpleName}-${case.operation.method}-${case.subject?.name ?: "Sources"}-${case.count}-${case.condition?.name ?: "Protocol"}",
            "${fixture.name}.${case.operation.method}",
            parameters,
            case.operation.boundary,
            control = case.count == 0 || case.condition == Condition.Settled,
        )
    }
}
