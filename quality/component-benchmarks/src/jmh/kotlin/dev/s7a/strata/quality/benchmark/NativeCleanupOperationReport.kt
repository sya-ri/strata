package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * All ten original complete native cleanup boundaries share their original verification lifetime.
 * Registry shapes grow in 0/1/32 order before its one permanent close; pending and settled devices retain original lifetimes.
 * Callback and retention assertions run after sampling, and cleanup failure prevents a complete report from returning.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object NativeCleanupOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    private enum class Operation(
        val method: String,
        val boundary: String,
    ) {
        Poll("registryPoll", "Poll every registered device and cleanup participant once."),
        Consumed("registryConsumed", "Settle GUI consumption and poll every registered device and cleanup participant once."),
        Pending("pendingFencePoll", "Poll every unchanged persistent unsignalled initialization fence once."),
        Settled("settledFenceCycle", "Reserve, transfer, seal and retire one fresh settled generation per original owner."),
    }

    override val rows: List<JvmOperationReportRow> =
        listOf(Operation.Poll, Operation.Consumed).flatMap { operation -> listOf(0, 1, 32).map { owners -> row(operation, owners) } } +
            listOf(Operation.Pending, Operation.Settled).flatMap { operation -> listOf(1, 32).map { owners -> row(operation, owners) } }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        val reports = linkedMapOf<String, JsonObject>()
        NativeCleanupBenchmark.withVerifiedOwners(
            registry = { owner ->
                registryReport(Operation.Poll, owner, plan).also { check(reports.put(row(Operation.Poll, owner.owners).id, it) == null) }
                registryReport(Operation.Consumed, owner, plan).also { check(reports.put(row(Operation.Consumed, owner.owners).id, it) == null) }
            },
            pending = { owner ->
                val id = row(Operation.Pending, owner.owners).id
                var before = owner.observedWork
                val report =
                    JvmPerformanceRunner
                        .measure(
                            id,
                            plan,
                            beforeSample = { before = owner.observedWork },
                            afterOperation = { _, result ->
                                check(result == owner.owners)
                                check(owner.observedWork == before.copy(queries = before.queries + owner.owners))
                            },
                        ) { owner.poll() }
                        .evidence
                check(reports.put(id, report) == null)
            },
            settled = { owner ->
                val id = row(Operation.Settled, owner.owners).id
                var before = owner.observedWork
                val report =
                    JvmPerformanceRunner
                        .measure(
                            id,
                            plan,
                            beforeSample = { before = owner.observedWork },
                            afterOperation = { _, result ->
                                check(result == before.resourceCloses + owner.owners)
                                check(
                                    owner.observedWork ==
                                        before.copy(
                                            queries = before.queries + owner.owners,
                                            fences = before.fences + owner.owners,
                                            fenceCloses = before.fenceCloses + owner.owners,
                                            resourceCloses = before.resourceCloses + owner.owners,
                                        ),
                                )
                                check(owner.retainedSets == 0)
                            },
                        ) { owner.cycle() }
                        .evidence
                check(reports.put(id, report) == null)
            },
        )
        check(reports.keys == rows.map { it.id }.toSet())
        return reports
    }

    /**
     * The original native verifier and collection run together because its process registry has one terminal lifetime.
     * [collect] performs that complete verifier itself; invoking the generic callback first would close the registry before sampling.
     */
    override fun collectVerified(
        plan: PerformancePlan,
        verifyWork: () -> Unit,
    ): Map<String, JsonObject> = collect(plan)

    private fun registryReport(
        operation: Operation,
        owner: NativeCleanupBenchmark.Registry,
        plan: PerformancePlan,
    ): JsonObject {
        val id = row(operation, owner.owners).id
        var before = owner.observedWork
        return JvmPerformanceRunner
            .measure(
                id,
                plan,
                beforeSample = { before = owner.observedWork },
                afterOperation = { _, result ->
                    check(result == owner.owners)
                    val expected =
                        before.copy(
                            polls = before.polls + owner.owners,
                            consumptions = before.consumptions + if (operation == Operation.Consumed) owner.owners else 0,
                        )
                    check(owner.observedWork == expected)
                    check(NativeCanvasDevices.retainedTargetCount() == 0)
                    check(NativeCanvasDevices.retainedGuiResourceSetCount() == 0)
                },
            ) {
                when (operation) {
                    Operation.Poll -> owner.poll()
                    Operation.Consumed -> owner.consumed()
                    Operation.Pending, Operation.Settled -> error("A registry report requires its original registry operation")
                }
            }.evidence
    }

    private fun row(
        operation: Operation,
        owners: Int,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-$owners",
            "${NativeCleanupBenchmark::class.java.name}.${operation.method}",
            mapOf("owners" to owners.toString()),
            operation.boundary,
        )
}
