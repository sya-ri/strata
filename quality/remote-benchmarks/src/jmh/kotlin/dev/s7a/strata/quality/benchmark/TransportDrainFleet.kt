package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteScreenService
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns negotiated native-free players and actual transport state for one worker.
 * Each cycle uses monotonically increasing packet/message identities and releases its current input/output bytes.
 * The source-verified per-peer runtime tick receives fixed logical timestamps on both revisions.
 * Timing includes the identical reflection adapter and excludes the public tick's clock/map snapshot/failure wrapper.
 * Ordinary public-tick contracts separately verify ownership and failed-peer removal.
 */
public class TransportDrainFleet(
    count: Int,
    private val workload: TransportDrainWorkload,
) : AutoCloseable {
    private val peers = List(count) { Peer(it) }
    private val producer = if (workload == TransportDrainWorkload.BusyProducer) Executors.newSingleThreadExecutor() else null
    private val service = RemoteScreenService<Int, Unit>(RemoteEndpoint.Server, { index, bytes -> peers[index].receive(bytes) }, { throw it })
    private val tickMethod =
        RemoteScreenService::class.java
            .getDeclaredMethod(
                "tick",
                Class.forName("${RemoteScreenService::class.java.name}\$Peer"),
                checkNotNull(Long::class.javaPrimitiveType),
            ).apply { isAccessible = true }
    private var logicalMillis = 0L
    private var generation = 0L
    private var closed = false

    init {
        require(count in setOf(1, 100, 1000))
        peers.forEach { peer ->
            peer.client =
                RemoteConnection(emptySet(), RemotePacket.limits) { bytes ->
                    peer.incoming.addLast(RemotePacket.encode(RemotePacket.Frame(checkNotNull(peer.address), peer.sequence++, bytes)))
                }
            service.join(peer.index)
            val retained = field(service.javaClass, "peers").get(service) as Map<*, *>
            val owner = checkNotNull(retained[peer.index])
            peer.retained = owner
            val address = RemoteAddress(RemoteEndpoint.Server, UUID(0, peer.index + 1L))
            field(owner.javaClass, "address").set(owner, address)
            field(RemotePacketStream::class.java, "address").set(field(owner.javaClass, "stream").get(owner), address)
            service.enqueue(peer.index, RemotePacket.encode(RemotePacket.Discovery))
        }
        tick()
        peers.forEach { peer ->
            peer.client.flush()
            peer.incoming.forEach { service.enqueue(peer.index, it) }
            peer.incoming.clear()
        }
        tick()
        val retained = field(service.javaClass, "peers").get(service) as Map<*, *>
        peers.forEach { peer ->
            checkNotNull(service.capabilities(peer.index))
            val owner = checkNotNull(retained[peer.index])
            peer.server = field(owner.javaClass, "connection").get(owner) as RemoteConnection
            peer.stream = field(owner.javaClass, "stream").get(owner) as RemotePacketStream
            peer.delivered = 0
        }
    }

    /**
     * Completes one bounded cycle, including preparation and all required tick continuations.
     * Busy ingress is published on another thread after the same peer's inbox/stream drain reaches output.
     */
    public fun cycle(): Long {
        check(closed.not())
        generation++
        val first = (generation - 1) * workload.frames + 1
        peers.forEach { peer ->
            repeat(workload.frames) { offset ->
                val message = RemoteMessage.Resynchronize(first + offset)
                peer.client.send(message)
                if (workload != TransportDrainWorkload.BusyProducer) peer.server.send(message)
            }
            if (0 < workload.frames) peer.client.flush(workload.frames)
        }
        when (workload) {
            TransportDrainWorkload.SequenceGap -> drainGap()
            TransportDrainWorkload.BusyProducer -> drainBusy()
            else -> drainAvailable()
        }
        return peers.sumOf { it.delivered }
    }

    /**
     * Admits a later sequence before the missing first frame, retaining it until the next logical tick.
     */
    private fun drainGap() {
        peers.forEach { peer -> service.enqueue(peer.index, peer.incoming.removeLast()) }
        tick()
        peers.forEach { peer -> service.enqueue(peer.index, peer.incoming.removeFirst()) }
        tick()
    }

    /**
     * Publishes immutable ingress on a producer after the same peer reaches its outgoing continuation.
     */
    private fun drainBusy() {
        peers.forEach { peer ->
            peer.afterWrite = {
                checkNotNull(producer)
                    .submit {
                        peer.incoming.forEach { service.enqueue(peer.index, it) }
                        peer.incoming.clear()
                    }
                    .get(10, TimeUnit.SECONDS)
            }
            peer.server.send(RemoteMessage.Resynchronize(generation))
        }
        repeat(3) { tick() }
    }

    /**
     * Supplies the current ready burst and completes every eight-frame outgoing budget it requires.
     */
    private fun drainAvailable() {
        peers.forEach { peer ->
            peer.incoming.forEach { service.enqueue(peer.index, it) }
            peer.incoming.clear()
        }
        repeat(maxOf(1, (workload.frames + 7) / 8)) { tick() }
    }

    /**
     * Confirms exact input/output counts, empty bounded queues and no retained service sessions outside timing.
     */
    public fun verifyDrained() {
        peers.forEach { peer ->
            check(peer.incoming.isEmpty())
            check(field(RemotePacketStream::class.java, "nextIncoming").getLong(peer.stream) == 2 + generation * workload.frames)
            check((field(RemotePacketStream::class.java, "pending").get(peer.stream) as Map<*, *>).isEmpty())
            check((field(RemoteConnection::class.java, "pending").get(peer.server) as Collection<*>).isEmpty())
            val expected = generation * if (workload == TransportDrainWorkload.BusyProducer) 1 else workload.frames
            check(peer.delivered == expected)
            checkNotNull(peer.server.capabilities)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            service.close()
            peers.forEach { peer ->
                peer.client.close()
                peer.incoming.clear()
                peer.afterWrite = null
                check(peer.server.capabilities == null)
                check((field(RemotePacketStream::class.java, "pending").get(peer.stream) as Map<*, *>).isEmpty())
            }
            check((field(service.javaClass, "peers").get(service) as Map<*, *>).isEmpty())
        } finally {
            producer?.let { worker ->
                worker.shutdownNow()
                check(worker.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    /**
     * Calls the unchanged actual peer-tick signature with one frozen logical timestamp for the complete fleet.
     * Reflective exception wrapping is removed so protocol/callback failure identity remains authoritative.
     */
    private fun tick() {
        logicalMillis++
        peers.forEach { peer ->
            try {
                tickMethod.invoke(service, peer.retained, logicalMillis)
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
    }

    private fun field(
        type: Class<*>,
        name: String,
    ): Field = type.getDeclaredField(name).apply { isAccessible = true }

    /**
     * Holds only the current routed frames and scalar sequence/delivery provenance for one authenticated player.
     */
    private class Peer(
        val index: Int,
    ) {
        var address: RemoteAddress? = null
        var sequence = 1L
        var delivered = 0L
        var lastMessage = 0L
        val incoming = ArrayDeque<ByteArray>()
        lateinit var retained: Any
        lateinit var client: RemoteConnection
        lateinit var server: RemoteConnection
        lateinit var stream: RemotePacketStream
        var afterWrite: (() -> Unit)? = null

        /**
         * Decodes actual output, checks the frozen message order and releases a one-shot late-arrival hook.
         */
        fun receive(bytes: ByteArray) {
            val packet = RemotePacket.decode(bytes) as RemotePacket.Frame
            if (address == null) address = packet.address
            check(packet.address == address)
            client.receive(packet.bytes, 0)?.let { message ->
                check(message is RemoteMessage.Resynchronize)
                check(message.session == lastMessage + 1)
                lastMessage = message.session
                delivered++
            }
            val callback = afterWrite
            afterWrite = null
            callback?.invoke()
        }
    }
}
