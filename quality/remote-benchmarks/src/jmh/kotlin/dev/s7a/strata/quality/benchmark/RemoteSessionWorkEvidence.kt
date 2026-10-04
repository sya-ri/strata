package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Untimed retention and patch admission using the actual retained-session benchmark fixtures.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object RemoteSessionWorkEvidence {
    /**
     * Verifies the complete separate matrix, idle output reuse, exact changed records and terminal release.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        check(JmhWorkloadInventory.capture(listOf(RemoteSessionBenchmark::class.java), setOf("avgt")).size == 9)
        RemoteSessionWorkload.entries.forEach { workload ->
            val fleet = RemoteSessionFleet(workload)
            fleet.use { _ ->
                check(fleet.nodeCount == workload.sessions * workload.nodes) { "Expected ${workload.sessions * workload.nodes} projected nodes, got ${fleet.nodeCount}; outputs=${fleet.latest.map { message -> message?.javaClass?.simpleName }}" }
                check(fleet.latest.all { it is RemoteMessage.Snapshot })
                check(fleet.subscriptions == workload.sessions)
                val emissions = fleet.emissions
                val initial = fleet.latest.toList()
                check(fleet.idle() == workload.sessions * workload.nodes)
                check(fleet.emissions == emissions && fleet.latest.indices.all { fleet.latest[it] === initial[it] })
                check(fleet.update() == workload.sessions * workload.nodes)
                check(fleet.emissions == emissions + workload.sessions)
                check(fleet.latest.all { (it as? RemoteMessage.Update)?.patch?.changed?.size == workload.nodes - 2 })
                val independent = RemoteSessionFleet(workload)
                independent.use { _ -> independent.update() }
                independent.verifyClosed()
                check(fleet.subscriptions == workload.sessions)
            }
            fleet.verifyClosed()
            println("Retained remote $workload: ${workload.sessions} owners, ${workload.sessions * workload.nodes} nodes, exact patches and zero terminal subscriptions")
        }
    }
}
