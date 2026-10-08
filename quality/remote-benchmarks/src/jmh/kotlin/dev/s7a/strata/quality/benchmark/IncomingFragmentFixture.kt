@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFrameInbox
import dev.s7a.strata.runtime.remote.RemoteFraming
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Native-independent real decoder/reorder/assembly and common-service ingress, with independent literal wire inputs.
 * One invocation owns only its current streams and outputs; immutable source envelopes belong to this trial.
 * The supplemental CPU meter isolates receive; JMH includes preparation, downstream byte/terminal checks and cleanup.
 */
@Suppress("TooManyFunctions") // Explicit preparation, operation and release boundaries support the same frozen fixture in both collectors.
public class IncomingFragmentFixture(
    private val owners: Int,
    private val workload: IncomingFragmentWorkload,
    private val route: IncomingFragmentRoute,
) : AutoCloseable {
    private val decoder = IncomingFragmentDecoder(route)
    private val limits = if (workload == IncomingFragmentWorkload.NegotiatedSmall) RemotePacket.limits.copy(frameBytes = 64) else RemotePacket.limits
    private val expected = ByteArray(workload.logicalBytes) { (it * 31 + 7).toByte() }
    private val fragmentCount = if (expected.isEmpty()) 0 else (expected.size - 1) / (limits.frameBytes - 16) + 1
    private val templateAddress = RemoteAddress(RemoteEndpoint.Server, UUID(0, 1))
    private val templates = packets(templateAddress)
    private var active = emptyList<TransferOwner>()
    private var phase = IncomingFragmentPhase.Admission

    init {
        require(owners in 1..8)
    }

    /**
     * Builds fresh execution owners, queues and assembly state, including stale/discovery preparation.
     * CPU collection calls this outside its interval; JMH includes it in the declared lifecycle cycle.
     * Server ingress uses actual service-owned peers and the original private receive phase signature on both revisions.
     * Their address is fixed before discovery, so every phase reuses exactly the same immutable literal envelopes.
     */
    public fun prepare(selected: IncomingFragmentPhase) {
        check(active.isEmpty())
        require(selected != IncomingFragmentPhase.ServerIngress || route == IncomingFragmentRoute.Production)
        phase = selected
        active = (0 until owners).map { TransferOwner(selected) }
        active.forEach { holder ->
            holder.owner.run {
                if (workload == IncomingFragmentWorkload.Stale) {
                    holder.frames.forEach { decoder.receive(holder.stream, it) }
                    assemble(holder)
                    holder.output = null
                }
            }
        }
    }

    /**
     * Performs only the selected interval, retaining at most one completed logical result per owner.
     * Reflection, logical-owner entry and all bounded drains required by the selected phase remain visible costs.
     */
    public fun receive(): Int {
        check(active.size == owners)
        active.forEach { holder ->
            holder.owner.run {
                when (phase) {
                    IncomingFragmentPhase.Admission -> holder.frames.forEach { decoder.receive(holder.stream, it) }
                    IncomingFragmentPhase.Assembly -> {
                        holder.frames.forEach { check(holder.inbox.offer(it)) }
                        while (true) {
                            val bytes = holder.inbox.poll() ?: break
                            decoder.receive(holder.stream, bytes)
                        }
                        assemble(holder)
                    }
                    IncomingFragmentPhase.ServerIngress -> {
                        val service = checkNotNull(holder.service)
                        holder.frames.forEach { service.enqueue(Unit, it) }
                        repeat((holder.frames.size + 63) / 64) { holder.receiveServer() }
                    }
                }
            }
        }
        return owners * templates.size
    }

    /**
     * Asserts exact complete bytes, no logical delivery for controls, bounded accounting and terminal release.
     * CPU collection calls this after the sample; JMH includes it in the lifecycle cycle.
     * Also verifies callback arrays remain stable through owner cleanup.
     */
    public fun verifyAndClose() {
        try {
            active.forEach { holder ->
                holder.owner.run {
                    if (phase != IncomingFragmentPhase.Assembly) assemble(holder)
                    val delivered = workload !in setOf(IncomingFragmentWorkload.Discovery, IncomingFragmentWorkload.Stale, IncomingFragmentWorkload.OtherIncarnation)
                    if (delivered) check(checkNotNull(holder.output).contentEquals(expected)) else check(holder.output == null)
                    check((field(holder.stream, "pending") as Map<*, *>).isEmpty())
                    check(field(holder.stream, "pendingBytes") == 0)
                    check(field(holder.stream, "gapSince") == null)
                    check(field(holder.framing, "pending") == null)
                }
            }
        } finally {
            close()
        }
    }

    /**
     * Untimed counts distinguish the necessary decode array from the removed queue copy and retained assembly copy.
     * Uses identity checks on actual decoder and queue storage, not allocation forecasts.
     */
    public fun workCounts(): Map<String, Long> {
        check(active.isEmpty())
        var decoded = 0L
        var decoderArrays = 0L
        var copied = 0L
        var admitted = 0L
        var admittedBytes = 0L
        RemotePacketStream(templateAddress, limits, {}).use { stream ->
            if (workload == IncomingFragmentWorkload.Stale) {
                templates.forEach { decoder.receive(stream, it) }
                stream.drain(0) { }
            }
            templates.forEach { bytes ->
                val before = (field(stream, "pending") as Map<*, *>).size
                val count = decoder.inspect(stream, bytes)
                decoded += count.first
                if (0 < count.first) decoderArrays++
                copied += count.second
                val after = (field(stream, "pending") as Map<*, *>).size
                if (before < after) {
                    admitted++
                    admittedBytes += count.first
                }
            }
        }
        return mapOf("native_packets" to templates.size.toLong(), "logical_bytes" to expected.size.toLong(), "admitted_fragments" to admitted, "admitted_fragment_bytes" to admittedBytes, "decoder_fragment_bytes" to decoded, "decoder_fragment_arrays" to decoderArrays, "queue_copied_bytes" to copied, "queue_copy_arrays" to if (copied == 0L) 0L else admitted, "assembly_bytes" to if (admitted == 0L) 0L else expected.size.toLong())
    }

    override fun close() {
        val previous = active
        active = emptyList()
        previous.forEach { holder ->
            holder.owner.run {
                val retained = holder.output
                holder.output = null
                holder.service?.close()
                holder.stream.close()
                holder.inbox.close()
                holder.framing.close()
                check((field(holder.stream, "pending") as Map<*, *>).isEmpty())
                check(field(holder.stream, "pendingBytes") == 0)
                check(field(holder.stream, "outgoing") == null)
                check(field(holder.framing, "pending") == null)
                if (retained != null) check(retained.contentEquals(expected))
            }
        }
    }

    private fun assemble(holder: TransferOwner) {
        val count = (field(holder.stream, "pending") as Map<*, *>).size
        repeat((count + 63) / 64) {
            holder.stream.drain(0) { bytes ->
                holder.framing.receive(bytes, 0)?.let { complete ->
                    check(holder.output == null)
                    holder.output = complete
                }
            }
        }
    }

    private fun packets(address: RemoteAddress): List<ByteArray> {
        if (workload == IncomingFragmentWorkload.Discovery) return listOf(byteArrayOf(0))
        val target = if (workload == IncomingFragmentWorkload.OtherIncarnation) address.copy(incarnation = UUID(0, -1)) else address
        val native =
            (0 until fragmentCount).map { index ->
                val offset = index * (limits.frameBytes - 16)
                val count = minOf(limits.frameBytes - 16, expected.size - offset)
                ByteBuffer.allocate(26 + 16 + count)
                    .put(1.toByte())
                    .put(target.endpoint.ordinal.toByte())
                    .putLong(target.incarnation.mostSignificantBits)
                    .putLong(target.incarnation.leastSignificantBits)
                    .putLong(index + 1L)
                    .putLong(1L)
                    .putInt(expected.size)
                    .putInt(offset)
                    .put(expected, offset, count)
                    .array()
            }
        return when (workload) {
            IncomingFragmentWorkload.Reversed -> native.reversed()
            IncomingFragmentWorkload.Gap -> native.drop(1) + native.take(1)
            IncomingFragmentWorkload.Duplicate -> native + native
            else -> native
        }
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /**
     * Independent current invocation state created and released under one serial execution owner.
     */
    private inner class TransferOwner(
        selected: IncomingFragmentPhase,
    ) {
        val owner = RuntimeExecutionOwner()
        val inbox = RemoteFrameInbox()
        val framing = RemoteFraming(limits)
        var output: ByteArray? = null
        val service =
            owner.run {
                if (selected == IncomingFragmentPhase.ServerIngress) RemoteScreenService<Unit, Unit>(RemoteEndpoint.Server, { _, _ -> }, { throw it }) else null
            }
        val peer =
            owner.run {
                service?.let { host ->
                    host.join(Unit)
                    checkNotNull((field(host, "peers") as Map<*, *>)[Unit]).also { retained ->
                        retained.javaClass.getDeclaredField("address").apply { isAccessible = true }.set(retained, templateAddress)
                        val retainedStream = field(retained, "stream") as RemotePacketStream
                        retainedStream.javaClass.getDeclaredField("address").apply { isAccessible = true }.set(retainedStream, templateAddress)
                    }
                }
            }
        val stream =
            owner.run { peer?.let { field(it, "stream") as RemotePacketStream } ?: RemotePacketStream(templateAddress, limits, {}) }
        val frames = templates
        private val serverPhase: Method? =
            peer?.let {
                checkNotNull(service).javaClass.getDeclaredMethod("receivePackets", it.javaClass, Long::class.javaPrimitiveType).apply { isAccessible = true }
            }

        init {
            owner.run {
                service?.let { host ->
                    host.enqueue(Unit, byteArrayOf(0))
                    receiveServer()
                    stream.limitTo(limits)
                }
            }
        }

        /**
         * Calls the actual bounded common ingress and preserves its original exception identity.
         */
        fun receiveServer() {
            try {
                checkNotNull(serverPhase).invoke(checkNotNull(service), checkNotNull(peer), 0L)
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
    }
}
