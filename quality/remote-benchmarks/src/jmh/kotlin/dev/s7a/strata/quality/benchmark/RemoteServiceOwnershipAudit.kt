package dev.s7a.strata.quality.benchmark

import java.lang.ref.WeakReference

/**
 * Untimed weak observations of actual private service owners, without injecting or replacing membership.
 * Reflection is bound to the unchanged peers/sessions field contract on both runtime sides and fails if it drifts.
 * Closed public handles stay reachable in the fleet while retired Peer, Active and plugin owners must collect.
 */
internal class RemoteServiceOwnershipAudit {
    private val observed = linkedMapOf<Int, MutableList<WeakReference<Any>>>()

    /**
     * Unique actual owners observed across phase boundaries, including retired owners already collected.
     */
    val observedCount: Int get() = observed.values.sumOf { it.size }

    /**
     * Captures only weak identities of current production owners at an existing fixture phase boundary.
     */
    fun capture(fleet: RemoteServiceFleet) {
        val peers = field(fleet.service, "peers") as Map<*, *>
        peers.values.filterNotNull().forEach { peer ->
            observe(peer)
            (field(peer, "sessions") as Map<*, *>).values.filterNotNull().forEach { active ->
                observe(active)
                observe(checkNotNull(field(active, "owner")))
            }
        }
    }

    /**
     * Rejects incomplete frozen operations using actual production queues, without draining or mutating them.
     * Field names bind the same connection/stream/inbox contract on both runtime sides and fail on drift.
     */
    fun verifyTransportDrained(fleet: RemoteServiceFleet) {
        val peers = field(fleet.service, "peers") as Map<*, *>
        peers.values.filterNotNull().forEach { peer ->
            verifyConnection(checkNotNull(field(peer, "connection")))
            verifyStream(checkNotNull(field(peer, "stream")))
            val inbox = checkNotNull(field(peer, "inbox"))
            synchronized(checkNotNull(field(inbox, "lock"))) {
                check((field(inbox, "frames") as Collection<*>).isEmpty())
                check(field(inbox, "pendingBytes") == 0)
            }
        }
        fleet.peers.values.forEach { peer ->
            verifyConnection(peer.connection)
            field(peer, "stream")?.let(::verifyStream)
            check(peer.inbound.isEmpty() && peer.outbound.isEmpty())
        }
    }

    /**
     * Requires collection after unwind and terminal cleanup, including reachable closed handles.
     */
    fun verifyReleased() {
        repeat(40) { if (observed.values.any { bucket -> bucket.any { it.get() != null } }) System.gc() }
        check(observed.values.all { bucket -> bucket.all { it.get() == null } }) { "A retired production Peer, Active or plugin owner remains reachable" }
    }

    private fun observe(value: Any) {
        val bucket = observed.getOrPut(System.identityHashCode(value)) { mutableListOf() }
        if (bucket.none { it.get() === value }) bucket += WeakReference(value)
    }

    private fun verifyConnection(connection: Any) {
        check((field(connection, "pending") as Collection<*>).isEmpty())
        check(field(connection, "queuedBytes") == 0 && field(connection, "queuedFrames") == 0)
        val framing = checkNotNull(field(connection, "framing"))
        check(field(framing, "pending") == null && field(framing, "received") == 0)
        check(field(framing, "receiving") == 0L && field(framing, "startedAt") == 0L)
    }

    private fun verifyStream(stream: Any) {
        check((field(stream, "pending") as Map<*, *>).isEmpty())
        check(field(stream, "pendingBytes") == 0)
        check(field(stream, "gapSince") == null)
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .also { it.isAccessible = true }
            .get(owner)
}
