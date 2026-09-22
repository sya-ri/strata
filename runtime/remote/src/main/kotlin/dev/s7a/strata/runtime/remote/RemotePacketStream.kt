package dev.s7a.strata.runtime.remote

import java.util.TreeMap

/**
 * Owner-thread ordering boundary for one host incarnation across asynchronous proxy message events.
 * Retains at most the negotiated pending bytes and entry count; gaps expire on monotonic adapter ticks.
 * Screen cancellation occupies its ordinary ordered frame position, so dropped transfers cannot leave sequence gaps.
 * Terminal close releases all queued bytes and the native writer.
 */
public class RemotePacketStream(
    public val address: RemoteAddress,
    private var limits: RemoteLimits = RemotePacket.limits,
    send: (ByteArray) -> Unit,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var outgoing: ((ByteArray) -> Unit)? = send
    private var nextOutgoing = 1L
    private var nextIncoming = 1L
    private val pending = TreeMap<Long, ByteArray>()
    private var pendingBytes = 0
    private var gapSince: Long? = null

    /**
     * Adds a strictly increasing envelope sequence immediately before native delivery.
     * Bootstrap greetings retain the fixed native bound even when peer negotiation has already tightened incoming admission.
     */
    public fun send(bytes: ByteArray) {
        checkOwner()
        require(bytes.size in 17..RemotePacket.limits.frameBytes) { "Invalid routed fragment length." }
        check(nextOutgoing < Long.MAX_VALUE) { "Remote packet identity space is exhausted." }
        checkNotNull(outgoing) { "Remote packet stream is closed." }(RemotePacket.encode(RemotePacket.Frame(address, nextOutgoing++, bytes)))
    }

    /**
     * Tightens admission after negotiation, rejecting already queued work that exceeds the negotiated bounds.
     */
    public fun limitTo(negotiated: RemoteLimits) {
        checkOwner()
        check(outgoing != null) { "Remote packet stream is closed." }
        if (limits == negotiated) return
        limits = limits.intersect(negotiated)
        if (limits.pendingBytes < pendingBytes || limits.collectionEntries < pending.size || pending.values.any { limits.frameBytes < it.size }) {
            throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Queued packets exceed negotiated limits.")
        }
    }

    /**
     * Copies a current-incarnation fragment into the bounded reorder queue; stale incarnations and duplicates are ignored.
     * Conflicting duplicate content, impossible identities, and queue exhaustion fail explicitly.
     */
    public fun offer(
        packet: RemotePacket.Frame,
        nowMillis: Long,
    ) {
        checkOwner()
        check(outgoing != null) { "Remote packet stream is closed." }
        if (packet.address != address) return
        require(packet.sequence in 1 until Long.MAX_VALUE && packet.bytes.size in 17..limits.frameBytes) { "Invalid routed fragment." }
        expire(nowMillis)
        if (packet.sequence < nextIncoming) return
        val previous = pending[packet.sequence]
        if (previous != null) {
            require(previous.contentEquals(packet.bytes)) { "Conflicting duplicate routed fragment." }
            return
        }
        if (limits.pendingBytes - pendingBytes < packet.bytes.size || limits.collectionEntries <= pending.size) {
            throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Remote packet reorder queue is full.")
        }
        pending[packet.sequence] = packet.bytes.copyOf()
        pendingBytes += packet.bytes.size
        updateGap(nowMillis)
    }

    /**
     * Delivers at most [maxFrames] consecutive fragments and checks deadlines even without new network input.
     */
    public fun drain(
        nowMillis: Long,
        maxFrames: Int = 64,
        receive: (ByteArray) -> Unit,
    ) {
        checkOwner()
        check(outgoing != null) { "Remote packet stream is closed." }
        require(0 < maxFrames)
        expire(nowMillis)
        repeat(maxFrames) {
            val bytes = pending.remove(nextIncoming) ?: return@repeat
            nextIncoming++
            pendingBytes -= bytes.size
            receive(bytes)
        }
        updateGap(nowMillis)
    }

    override fun close() {
        checkOwner()
        outgoing = null
        pending.clear()
        pendingBytes = 0
        gapSince = null
    }

    private fun updateGap(nowMillis: Long) {
        gapSince = if (pending.isNotEmpty() && pending.containsKey(nextIncoming).not()) gapSince ?: nowMillis else null
    }

    private fun expire(nowMillis: Long) {
        val started = gapSince ?: return
        if (nowMillis < started || limits.assemblyMillis <= nowMillis - started) throw RemoteProtocolException(RemoteFailure.TimedOut, "Remote packet sequence gap timed out.")
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Remote packet stream belongs to another thread." }
    }
}
