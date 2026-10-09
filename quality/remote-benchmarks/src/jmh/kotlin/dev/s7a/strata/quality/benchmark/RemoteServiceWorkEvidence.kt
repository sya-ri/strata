package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.quality.benchmark.RemoteServiceTrace.WorkSequence
import dev.s7a.strata.runtime.remote.RemoteMessage

/**
 * Exact generated timed matrix and real public-route work oracle, separate from JMH elapsed/allocation scores.
 */
internal object RemoteServiceWorkEvidence {
    /**
     * Executes all frozen operations, including zero-source controls and the additional churn peer.
     */
    fun verify() {
        val fixtures = listOf(RemoteServiceBenchmark::class.java)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt")).size == 116)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt", "sample")).size == 232)
        check(RemoteServiceTopology.entries.size == 25)
        check(RemoteServiceTopology.entries.count { it.sessions == 0 } == 4)
        RemoteServiceTopology.entries.forEach { topology ->
            verifySteady(topology)
            verifyLifecycle(topology)
            println("Negotiated service $topology: stable/update/churn/lifecycle work and terminal subscriptions verified")
        }
        RemoteServiceGateEvidence.verify()
    }

    /**
     * Preserves the actual setup, stable/publication/churn and terminal prefix for each generated topology.
     */
    private fun verifySteady(topology: RemoteServiceTopology) {
        val transport = RemoteServiceOwnershipAudit()
        val fleet = RemoteServiceFleet(topology)
        val trace = RemoteServiceTrace(topology = fleet.topology, workSequence = WorkSequence.Steady)
        observe(fleet, trace)
        try {
            runCatching {
                fleet.use { _ ->
                    fleet.establish()
                    check(fleet.ticks == 3L + fleet.topology.deliveryTicks)
                    check(fleet.handles.size == topology.sessions && fleet.subscriptions == topology.sessions)
                    check(fleet.evaluations == topology.sessions.toLong())
                    check(fleet.nodeCount == topology.sessions * 3)
                    val packets = fleet.peers.values.sumOf { it.packets }
                    check(packets == topology.peers.toLong() + topology.sessions)
                    transport.verifyTransportDrained(fleet)
                    val evaluations = fleet.evaluations
                    check(fleet.idle() == topology.peers)
                    check(fleet.ticks == 4L + fleet.topology.deliveryTicks && fleet.evaluations == evaluations)
                    check(fleet.peers.values.sumOf { it.packets } == packets)
                    transport.verifyTransportDrained(fleet)
                    if (0 < topology.sessions) verifyPublications(fleet, transport, trace)
                    if (0 < topology.peers) verifyChurn(fleet, transport)
                }
                fleet.verifyClosed()
                transport.verifyTransportDrained(fleet)
            }.onFailure(trace::verificationFailure).getOrThrow()
        } finally {
            emit(fleet, trace)
        }
    }

    /**
     * Retains every actually decoded update while verifying one-owner and all-owner publication counts.
     */
    private fun verifyPublications(
        fleet: RemoteServiceFleet,
        transport: RemoteServiceOwnershipAudit,
        trace: RemoteServiceTrace,
    ) {
        val topology = fleet.topology
        val messages = mutableListOf<RemoteMessage>()
        val evaluations = fleet.evaluations
        val packets = fleet.peers.values.sumOf { it.packets }
        fleet.peers.values.forEach { peer ->
            peer.inspect = { message ->
                trace.message(peer.player, message)
                messages += message
            }
        }
        check(fleet.oneSource() == topology.peers)
        check(fleet.publications == 1L && fleet.evaluations == evaluations + 1)
        check(messages.size == 1 && (messages.single() as RemoteMessage.Update).patch.changed.size == 1)
        check(fleet.peers.values.sumOf { it.packets } == packets + 1)
        transport.verifyTransportDrained(fleet)
        messages.clear()
        val updateTicks = fleet.ticks
        check(fleet.allSources() == topology.peers)
        check(fleet.ticks == updateTicks + topology.deliveryTicks)
        check(fleet.publications == 1L + topology.sessions)
        check(fleet.evaluations == evaluations + 1 + topology.sessions)
        check(messages.size == topology.sessions && messages.all { (it as? RemoteMessage.Update)?.patch?.changed?.size == 1 })
        check(fleet.nodeCount == topology.sessions * 3)
        check(fleet.peers.values.sumOf { it.packets } == packets + 1 + topology.sessions)
        transport.verifyTransportDrained(fleet)
        fleet.peers.values.forEach { peer -> peer.inspect = { message -> trace.message(peer.player, message) } }
    }

    /**
     * Verifies the actual additional peer and its terminal release without excluding its work or output.
     */
    private fun verifyChurn(
        fleet: RemoteServiceFleet,
        transport: RemoteServiceOwnershipAudit,
    ) {
        val topology = fleet.topology
        val ticks = fleet.ticks
        check(fleet.churn() == topology.peers)
        check(fleet.ticks == ticks + 4 + topology.deliveryTicks)
        check(fleet.peers.size == topology.peers && fleet.handles.size == topology.sessions)
        check(fleet.subscriptions == topology.sessions)
        check(fleet.nodeCount == topology.sessions * 3)
        transport.verifyTransportDrained(fleet)
    }

    /**
     * Retains the complete real lifecycle prefix, including explicit zero-source controls and close output.
     */
    private fun verifyLifecycle(topology: RemoteServiceTopology) {
        val transport = RemoteServiceOwnershipAudit()
        val lifecycle = RemoteServiceFleet(topology)
        val trace = RemoteServiceTrace(topology = lifecycle.topology, workSequence = WorkSequence.Lifecycle)
        observe(lifecycle, trace)
        try {
            runCatching {
                lifecycle.use { _ ->
                    lifecycle.establish()
                    check(lifecycle.finishLifecycle() == topology.peers)
                    check(lifecycle.nodeCount == topology.sessions * 3)
                    check(lifecycle.ticks == 5L + lifecycle.topology.deliveryTicks)
                    check(lifecycle.publications == if (topology.sessions == 0) 0L else 1L)
                    check(lifecycle.evaluations == topology.sessions.toLong() + lifecycle.publications)
                    check(lifecycle.peers.values.sumOf { it.packets } == topology.peers.toLong() + topology.sessions + lifecycle.publications)
                    transport.verifyTransportDrained(lifecycle)
                }
                lifecycle.verifyClosed()
                transport.verifyTransportDrained(lifecycle)
            }.onFailure(trace::verificationFailure).getOrThrow()
        } finally {
            emit(lifecycle, trace)
        }
    }

    /**
     * Attaches detached observation only to this untimed verifier; JMH fleets retain their absent inspectors.
     * The default report callback still propagates the original failure after recording it.
     */
    private fun observe(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.reporter = { failure ->
            trace.failure(failure)
            throw failure
        }
        fleet.listener = { player, event -> trace.event(player, event) }
        fleet.peerJoined = { peer -> peer.inspect = { message -> trace.message(peer.player, message) } }
        fleet.phaseInspector = { trace.phase(fleet) }
        fleet.packetInspector = { player, direction, packet -> trace.packet(player, direction, packet) }
    }

    /**
     * Emits the complete observed prefix even when an assertion or runtime operation fails.
     * A printed record alone is never evidence of a successful process exit or accepted parity.
     */
    private fun emit(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        try {
            trace.addresses = fleet.peers.values.map { it.player to it.address }
            trace.work = listOf(fleet.ticks, fleet.publications, fleet.evaluations, fleet.peers.values.sumOf { it.packets }, fleet.peers.values.sumOf { it.bytes })
            trace.phase(fleet)
        } finally {
            println(Gson().toJson(trace))
        }
    }
}
