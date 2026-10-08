@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Actual message-codec/connection/framing/native-stream cycles with an independent literal wire reference.
 * Public controls deliver the original inner-only callback layout; server cycles use real service-owned connections.
 * Preparation, output byte checks, identity probes and cleanup stay outside supplemental CPU intervals.
 * JMH includes complete invocation lifecycles, avoiding invocation timestamps for short controls.
 */
@Suppress("TooManyFunctions") // Separate source, preparation, operation, parity and release are required by the shared collectors.
public class OutgoingFragmentFixture(
    private val owners: Int,
    private val workload: OutgoingFragmentWorkload,
    private val route: OutgoingFragmentRoute,
) : AutoCloseable {
    private val factory = OutgoingFragmentConnection()
    private val address = RemoteAddress(RemoteEndpoint.Server, UUID(0, 1))
    private val limits =
        when (workload) {
            OutgoingFragmentWorkload.NegotiatedSmall -> RemotePacket.limits.copy(frameBytes = 64)
            OutgoingFragmentWorkload.EntryCapacity -> RemoteLimits(frameBytes = 64, messageBytes = 256, pendingBytes = 1024, collectionEntries = 2, treeNodes = 1)
            OutgoingFragmentWorkload.ByteCapacity -> RemoteLimits(frameBytes = 64, messageBytes = 256, pendingBytes = 256, collectionEntries = 256, treeNodes = 1)
            else -> RemotePacket.limits
        }
    private val codec = RemoteMessageCodec()
    private val messages = source()
    private val encoded = messages.map(codec::encode)
    private val referenceOutput = expected(if (workload == OutgoingFragmentWorkload.Hello) 1 else 2)
    private var active = emptyList<Owner>()
    private var phase = OutgoingFragmentPhase.Cycle

    init {
        require(owners in 1..8)
    }

    /** Builds independent current owners and actual negotiated connections before a selected CPU interval. */
    public fun prepare(selected: OutgoingFragmentPhase) {
        check(active.isEmpty())
        require(selected != OutgoingFragmentPhase.ServerCycle || route == OutgoingFragmentRoute.Production)
        phase = selected
        active = (0 until owners).map { Owner(selected, false) }
        if (phase == OutgoingFragmentPhase.Flush) active.forEach { it.owner.run { it.produce() } }
    }

    /** Runs only the selected actual queue/control, flush or complete production interval. */
    public fun transfer(): Int {
        check(active.size == owners)
        active.forEach { holder ->
            holder.owner.run {
                when (phase) {
                    OutgoingFragmentPhase.Queue -> holder.produce()
                    OutgoingFragmentPhase.Flush -> holder.flush()
                    OutgoingFragmentPhase.Cycle, OutgoingFragmentPhase.ServerCycle -> {
                        holder.produce()
                        holder.flush()
                    }
                }
            }
        }
        return active.sumOf { it.output.size }
    }

    /** Checks every complete native/inner byte, sequence and retained callback array, then releases all current state. */
    public fun verifyAndClose() {
        try {
            active.forEach { holder ->
                holder.owner.run {
                    if (phase == OutgoingFragmentPhase.Queue) holder.flush()
                    holder.verify()
                }
            }
        } finally {
            close()
        }
    }

    /**
     * Untimed identity probes count actual queued/delivered array payload, envelope copies and peak reserved headroom.
     * Failed unpublished partial construction is outside these queue observations; total allocation remains a meter/profiler result.
     * Discarded queued arrays are counted even when no native callback receives them.
     */
    public fun workCounts(selected: OutgoingFragmentPhase): Map<String, Long> {
        check(active.isEmpty())
        val holder = Owner(selected, true)
        return holder.owner.run {
            try {
                holder.produce()
                val queueDelivered = holder.output.size
                holder.flush()
                holder.verify()
                holder.counts(selected, queueDelivered)
            } finally {
                holder.close()
            }
        }
    }

    override fun close() {
        val previous = active
        active = emptyList()
        previous.forEach { holder -> holder.owner.run { holder.close() } }
    }

    private fun source(): List<RemoteMessage> {
        fun action(session: Long, bytes: Int): RemoteMessage.Action =
            RemoteMessage.Action(session, 1, 1, BuiltinProjection.PointerPress.type, ProjectionValue.Bytes(ByteArray(bytes) { (it * 31 + 7).toByte() }))
        val overhead = codec.encode(action(1, 0)).size
        fun sized(bytes: Int): RemoteMessage.Action = action(1, bytes - overhead)
        return when (workload) {
            OutgoingFragmentWorkload.Hello -> listOf(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, emptySet()))
            OutgoingFragmentWorkload.Control -> listOf(RemoteMessage.Resynchronize(1))
            OutgoingFragmentWorkload.SmallAction -> listOf(action(1, 48))
            OutgoingFragmentWorkload.OneFragment -> listOf(sized(24534))
            OutgoingFragmentWorkload.MultiFragment -> listOf(sized(24535))
            OutgoingFragmentWorkload.OneMiB -> listOf(sized(1048576))
            OutgoingFragmentWorkload.NearLimit -> listOf(sized(limits.messageBytes - 1))
            OutgoingFragmentWorkload.NegotiatedSmall -> listOf(sized(4096))
            OutgoingFragmentWorkload.ImmediateMany, OutgoingFragmentWorkload.DelayedMany -> (1L..8L).map { action(it, 48) }
            OutgoingFragmentWorkload.UnsentCancellation, OutgoingFragmentWorkload.PartialCancellation -> listOf(sized(1048576), RemoteMessage.Resynchronize(2))
            OutgoingFragmentWorkload.EntryCapacity, OutgoingFragmentWorkload.ByteCapacity -> (1L..64L).map { RemoteMessage.Resynchronize(it) }
        }
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /** Current invocation state; no probe arrays or historical outputs survive close. */
    private inner class Owner(
        selected: OutgoingFragmentPhase,
        private val probe: Boolean,
    ) : AutoCloseable {
        val owner = RuntimeExecutionOwner()
        val output = mutableListOf<ByteArray>()
        private val server = selected == OutgoingFragmentPhase.ServerCycle
        private val service = owner.run { if (server) RemoteScreenService<Unit, Unit>(RemoteEndpoint.Server, { _, bytes -> output.add(bytes) }, { throw it }) else null }
        private val peer =
            owner.run {
                service?.let { host ->
                    host.join(Unit)
                    checkNotNull((field(host, "peers") as Map<*, *>)[Unit]).also { retained ->
                        retained.javaClass.getDeclaredField("address").apply { isAccessible = true }.set(retained, address)
                        val retainedStream = field(retained, "stream") as RemotePacketStream
                        retainedStream.javaClass.getDeclaredField("address").apply { isAccessible = true }.set(retainedStream, address)
                    }
                }
            }
        private val stream = owner.run { peer?.let { field(it, "stream") as RemotePacketStream } ?: RemotePacketStream(address, send = output::add) }
        private val connection =
            owner.run {
                peer?.let { field(it, "connection") as RemoteConnection } ?: if (route == OutgoingFragmentRoute.Public) RemoteConnection(emptySet(), limits, output::add) else factory.create(limits, stream)
            }
        private val serverTick = peer?.let { serverMethod("tick", it) }
        private val serverReceive = peer?.let { serverMethod("receivePackets", it) }
        private var failed = false
        private var attempts = 0
        private var peakFrames = 0L
        private val observed = mutableListOf<ByteArray>()
        private val transferred = mutableListOf<ByteArray>()
        private val privateNative = route == OutgoingFragmentRoute.Production && factory.hasNativeFactory

        init {
            owner.run {
                if (workload != OutgoingFragmentWorkload.Hello) {
                    if (server) {
                        checkNotNull(service).enqueue(Unit, byteArrayOf(0))
                        invoke(serverTick)
                        val hello = codec.encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, emptySet()))
                        reference(hello, 1, 1, RemotePacket.limits, true).forEach { service.enqueue(Unit, it) }
                        invoke(serverTick)
                    } else {
                        connection.start()
                        connection.flush(100)
                        val hello = codec.encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, emptySet()))
                        reference(hello, 1, 1, RemotePacket.limits, false).forEach { check(connection.receive(it, 0) == null) }
                    }
                    check(connection.capabilities?.limits == limits)
                }
                output.clear()
                val first = if (route == OutgoingFragmentRoute.Public || workload == OutgoingFragmentWorkload.Hello) 1L else 2L
                check(field(stream, "nextOutgoing") == first)
            }
        }

        fun produce() {
            if (workload == OutgoingFragmentWorkload.Hello) {
                attempts++
                if (server) {
                    checkNotNull(service).enqueue(Unit, byteArrayOf(0))
                    if (probe) {
                        invoke(serverReceive)
                        observe()
                    }
                } else {
                    connection.start()
                    observe()
                }
                return
            }
            for ((index, message) in messages.withIndex()) {
                attempts++
                if (enqueue(message).not()) break
                observe()
                if (workload == OutgoingFragmentWorkload.ImmediateMany) flush()
                if (index == 0 && workload == OutgoingFragmentWorkload.PartialCancellation) connection.flush(1)
            }
            if (workload in setOf(OutgoingFragmentWorkload.UnsentCancellation, OutgoingFragmentWorkload.PartialCancellation)) {
                connection.discardSession(1)
                observe()
            }
            if (workload in setOf(OutgoingFragmentWorkload.EntryCapacity, OutgoingFragmentWorkload.ByteCapacity)) check(failed)
        }

        fun flush() {
            if (failed) return
            if (server && workload == OutgoingFragmentWorkload.Hello) invoke(serverTick)
            while (0 < (field(connection, "queuedFrames") as Int)) {
                if (server) invoke(serverTick) else connection.flush()
            }
        }

        fun verify() {
            check(output.size == referenceOutput.size)
            output.indices.forEach { index -> check(output[index].contentEquals(referenceOutput[index])) }
            check(field(connection, "queuedFrames") == 0)
            check(field(connection, "queuedBytes") == 0)
            check((field(connection, "pending") as Collection<*>).isEmpty())
            if (failed) {
                check(field(connection, "outgoing") == null)
                check(connection.capabilities == null)
            }
            if (probe) observed.forEach { array -> if (output.any { delivered(array, it) }) transferred.add(array) }
        }

        fun counts(selected: OutgoingFragmentPhase, queueDelivered: Int): Map<String, Long> {
            val scoped =
                when (selected) {
                    OutgoingFragmentPhase.Queue -> output.take(queueDelivered)
                    OutgoingFragmentPhase.Flush -> output.drop(queueDelivered)
                    OutgoingFragmentPhase.Cycle, OutgoingFragmentPhase.ServerCycle -> output
                }
            val scopedCopies = if (route == OutgoingFragmentRoute.Public) emptyList() else scoped.filter { array -> observed.none { it === array } }
            val copied = if (route == OutgoingFragmentRoute.Public) 0L else output.filter { array -> observed.none { it === array } }.sumOf { (it.size - 26).toLong() }
            return mapOf(
                "logical_codec_bytes" to encoded.take(attempts).sumOf { it.size.toLong() },
                "timed_logical_codec_bytes" to if (selected == OutgoingFragmentPhase.Flush) 0L else encoded.take(attempts).sumOf { it.size.toLong() },
                "scoped_delivered_fragments" to scoped.size.toLong(),
                "timed_native_fragment_copy_bytes" to scopedCopies.sumOf { (it.size - 26).toLong() },
                "timed_native_fragment_copy_arrays" to scopedCopies.size.toLong(),
                "attempted_messages" to attempts.toLong(),
                "observed_queued_fragments" to observed.size.toLong(),
                "observed_queued_array_payload_bytes" to observed.sumOf { it.size.toLong() },
                "observed_intermediate_fragment_payload_bytes" to if (route == OutgoingFragmentRoute.Production && privateNative.not()) observed.sumOf { it.size.toLong() } else 0L,
                "discarded_queued_array_payload_bytes" to observed.filter { array -> transferred.none { it === array } }.sumOf { it.size.toLong() },
                "delivered_fragments" to output.size.toLong(),
                "delivered_array_payload_bytes" to output.sumOf { it.size.toLong() },
                "native_fragment_copy_bytes" to copied,
                "native_fragment_copy_arrays" to if (route == OutgoingFragmentRoute.Public) 0L else output.count { array -> observed.none { it === array } }.toLong(),
                "peak_queued_fragments" to peakFrames,
                "peak_reserved_headroom_bytes" to if (privateNative) Math.multiplyExact(26L, peakFrames) else 0L,
            )
        }

        override fun close() {
            val retained = output.toList()
            val bytes = retained.map { it.copyOf() }
            service?.close()
            connection.close()
            stream.close()
            check(field(connection, "outgoing") == null)
            check((field(connection, "pending") as Collection<*>).isEmpty())
            check(field(stream, "outgoing") == null)
            retained.indices.forEach { index -> check(retained[index].contentEquals(bytes[index])) }
            observed.clear()
            transferred.clear()
            output.clear()
        }

        private fun observe() {
            if (probe.not()) return
            val queued = (field(connection, "pending") as Collection<*>).flatMap { transfer ->
                val storage = checkNotNull(field(checkNotNull(transfer), "frames"))
                val arrays = if (storage is Collection<*>) storage else field(storage, "frames") as Collection<*>
                arrays.map { it as ByteArray }
            }
            peakFrames = maxOf(peakFrames, queued.size.toLong())
            queued.forEach { array -> if (observed.none { it === array }) observed.add(array) }
        }

        private fun delivered(queued: ByteArray, bytes: ByteArray): Boolean {
            if (queued === bytes) return true
            if (route == OutgoingFragmentRoute.Public || privateNative) return false
            return queued.size == bytes.size - 26 && queued.indices.all { index -> queued[index] == bytes[index + 26] }
        }

        private fun enqueue(message: RemoteMessage): Boolean =
            try {
                connection.send(message)
                true
            } catch (failure: RemoteProtocolException) {
                check(workload in setOf(OutgoingFragmentWorkload.EntryCapacity, OutgoingFragmentWorkload.ByteCapacity))
                check(failure.reason == RemoteFailure.ResourceLimit)
                check(failure.message == "Remote send queue is full.")
                failed = true
                false
            }

        private fun serverMethod(name: String, retained: Any): Method =
            checkNotNull(service).javaClass.getDeclaredMethod(name, retained.javaClass, Long::class.javaPrimitiveType).apply { isAccessible = true }

        private fun invoke(method: Method?) {
            try {
                checkNotNull(method).invoke(checkNotNull(service), checkNotNull(peer), 0L)
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
    }

    private fun expected(firstSequence: Long): List<ByteArray> {
        if (workload in setOf(OutgoingFragmentWorkload.EntryCapacity, OutgoingFragmentWorkload.ByteCapacity)) return emptyList()
        val native = route == OutgoingFragmentRoute.Production
        val all = encoded.mapIndexed { index, bytes -> reference(bytes, index + 1L, 1, if (workload == OutgoingFragmentWorkload.Hello) RemotePacket.limits else limits, false) }
        val inner =
            when (workload) {
                OutgoingFragmentWorkload.UnsentCancellation -> all[1]
                OutgoingFragmentWorkload.PartialCancellation -> listOf(all[0].first(), ByteBuffer.allocate(17).putLong(1).putInt(0).putInt(0).put(0).array()) + all[1]
                else -> all.flatten()
            }
        return if (native) inner.mapIndexed { index, bytes -> envelope(bytes, firstSequence + index) } else inner
    }

    private fun reference(
        bytes: ByteArray,
        identity: Long,
        sequence: Long,
        bounds: RemoteLimits,
        native: Boolean,
    ): List<ByteArray> =
        (0 until (bytes.size - 1) / (bounds.frameBytes - 16) + 1).map { index ->
            val offset = index * (bounds.frameBytes - 16)
            val count = minOf(bounds.frameBytes - 16, bytes.size - offset)
            val inner = ByteBuffer.allocate(16 + count).putLong(identity).putInt(bytes.size).putInt(offset).put(bytes, offset, count).array()
            if (native) envelope(inner, sequence + index) else inner
        }

    private fun envelope(bytes: ByteArray, sequence: Long): ByteArray =
        ByteBuffer.allocate(26 + bytes.size).put(1.toByte()).put(0.toByte()).putLong(0).putLong(1).putLong(sequence).put(bytes).array()
}
