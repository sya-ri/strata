package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.messages.ChannelMessageSource
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFrameInbox
import dev.s7a.strata.runtime.remote.RemoteFraming
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.runtime.velocity.VelocityScreensTest.Harness
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Actual public plugin callback with independent authenticated actors and literal full-transfer inputs.
 * Uses the original captured ticker exclusively on its actual UI executor; no route or owner drain is copied.
 * Requires the same JDK concurrent-package opens as the existing worker fixture.
 * Setup, event creation, owner preparation/verification/waits and all input/output assertions are outside callback timing.
 */
@Suppress("TooManyFunctions") // Explicit callback/owner/probe/verification/cleanup boundaries serve the same frozen shared-meter fixture.
internal class NativeRoutingFixture(
    count: Int,
    private val workload: NativeRoutingWorkload,
    private val direction: NativeRoutingDirection,
) : AutoCloseable {
    private val harness = Harness()
    val plugin = harness.plugin
    val actors = (0 until count).map { NativeRoutingPlayer() }
    private val packets = actors.indices.map { NativeRoutingInputs.packets(it, workload, direction) }
    private val flatPackets = packets.flatten()
    private val service = field(plugin.javaClass, "screens").get(plugin) as VelocityScreenService
    private val executor = field(service.javaClass, "executor").get(service) as ScheduledExecutorService
    private val worker = field(service.javaClass, "owner").get(service) as Thread
    @Suppress("UNCHECKED_CAST") // The actual Velocity service owns RemoteScreenService<Player, Any>.
    private val host = field(service.javaClass, "host").get(service) as RemoteScreenService<Player, Any>
    private var events = emptyList<PluginMessageEvent>()
    private val results = mutableListOf<EventTask?>()
    private var peers = emptyList<Any>()
    private var closed = false
    private val ticker =
        runCatching {
            onOwner {
                val scheduled = field(service.javaClass, "ticker").get(service) as ScheduledFuture<*>
                val callable = checkNotNull(field(FutureTask::class.java, "callable").get(scheduled))
                val action = field(callable.javaClass, "task").get(callable) as Runnable
                check(scheduled.cancel(false))
                action
            }
        }.getOrElse { failure ->
            harness.close()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            throw failure
        }

    init {
        require(count in setOf(1, 8))
    }

    /**
     * Restores current runtime peers and fixes each incarnation before preparing events outside sampling.
     */
    fun prepare() {
        check(events.isEmpty())
        field(plugin.javaClass, "screens").set(plugin, service)
        actors.forEach { actor ->
            actor.current.set(actor.backend)
            actor.writeFailure = null
        }
        onOwner {
            val disconnected = actors.map { service.disconnect(it.player) }
            ticker.run()
            disconnected.forEach { check(it.isDone && it.isCompletedExceptionally.not()) }
            val discovered = actors.map { service.discover(it.player) }
            ticker.run()
            discovered.forEach { check(it.isDone && it.isCompletedExceptionally.not()) }
            val retained = field(host.javaClass, "peers").get(host) as Map<*, *>
            peers = actors.map { actor -> checkNotNull(retained[actor.player]) }
            peers.forEachIndexed { index, peer ->
                val address = RemoteAddress(RemoteEndpoint.Proxy, UUID(0, index + 1L))
                field(peer.javaClass, "address").set(peer, address)
                val stream = field(peer.javaClass, "stream").get(peer) as RemotePacketStream
                field(stream.javaClass, "address").set(stream, address)
                var sequence = 1L
                val greeting = RemoteMessageCodec().encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, RemotePacket.limits, emptySet()))
                RemoteFraming(RemotePacket.limits).use { framing ->
                    framing.send(greeting) { bytes -> host.enqueue(actors[index].player, RemotePacket.encode(RemotePacket.Frame(address, sequence++, bytes))) }
                }
            }
            ticker.run()
            actors.forEach { check(host.capabilities(it.player) != null) }
        }
        actors.forEach { actor ->
            actor.clear()
            when (workload) {
                NativeRoutingWorkload.StaleBackend -> actor.current.set(actor.replacement(equalAliases = true))
                NativeRoutingWorkload.AbsentBackend -> actor.current.set(null)
                else -> Unit
            }
        }
        if (workload == NativeRoutingWorkload.AbsentService) field(plugin.javaClass, "screens").set(plugin, null)
        val channel = if (workload == NativeRoutingWorkload.WrongChannel) MinecraftChannelIdentifier.from("other:channel") else VelocityScreenService.CHANNEL
        val unknown = object : ChannelMessageSource { }
        events = actors.flatMapIndexed { index, actor ->
            packets[index].map { bytes ->
                val source: ChannelMessageSource = if (workload == NativeRoutingWorkload.UnknownSender) unknown else if (direction == NativeRoutingDirection.BackendClient) actor.backend else actor.player
                PluginMessageEvent(source, actor.player, channel, bytes).also { event ->
                    if (workload == NativeRoutingWorkload.AlreadyHandled) event.result = PluginMessageEvent.ForwardResult.handled()
                }
            }
        }
    }

    /**
     * Invokes only the actual plugin callback for the complete native corpus; no owner work or event construction occurs here.
     */
    fun callback(): Int {
        check(events.isNotEmpty())
        events.forEach { results.add(plugin.message(it)) }
        return events.size
    }

    /**
     * Public full decoding control; invalid envelopes preserve their actual exception and never enter the plugin handler.
     */
    fun decodePublic(): Int =
        flatPackets.sumOf { bytes ->
            val decoded = runCatching { RemotePacket.decode(bytes) }.getOrNull()
            if (decoded is RemotePacket.Frame) decoded.bytes.size else 0
        }

    /**
     * Detached invariant input counts, independent of either runtime's allocation strategy.
     */
    fun inputCounts(): Map<String, Long> = mapOf("input_packets" to flatPackets.size.toLong(), "input_native_bytes" to flatPackets.sumOf { it.size.toLong() }, "owner_ticks" to maxOf(1, (packets.first().size + 63) / 64).toLong())

    /**
     * Runs only real captured ticks on their existing worker; declared tick count is identical for both runtimes.
     */
    fun processOwner(): Int {
        check(Thread.currentThread() === worker)
        val count = maxOf(1, (packets.first().size + 63) / 64)
        repeat(count) { ticker.run() }
        return count
    }

    /**
     * Runs inline on the existing owner or dispatches from the coordinator; dispatch/wait stays outside owner samples.
     */
    fun <T> onOwner(operation: () -> T): T =
        if (Thread.currentThread() === worker) operation() else executor.submit(Callable(operation)).get(5, TimeUnit.MINUTES)

    /**
     * Independent expected output/handled/inbox matrix before any asynchronous owned work is allowed to run.
     */
    fun verifyCallback(): Map<String, Long> {
        check(results.size == events.size)
        val validSource = skipsRouting().not() && invalidPacket().not() && workload != NativeRoutingWorkload.UnknownSender
        val expectsTask = validSource && workload == NativeRoutingWorkload.Discovery && direction != NativeRoutingDirection.BackendClient
        events.forEachIndexed { index, event ->
            check(event.result.isAllowed == (workload == NativeRoutingWorkload.WrongChannel))
            check((results[index] != null) == expectsTask)
        }
        val observations = onOwner { actors.mapIndexed(::verifyActor) }
        val fields = listOf("forwarded_packets", "forwarded_bytes", "owner_inbox_packets", "owner_inbox_snapshot_bytes")
        return mapOf("callback_packets" to events.size.toLong()) + fields.associateWith { key -> observations.sumOf { it.getValue(key) } }
    }

    private fun verifyActor(index: Int, actor: NativeRoutingPlayer): Map<String, Long> {
        val proxy = direction == NativeRoutingDirection.ClientProxy || workload == NativeRoutingWorkload.ProxyImpersonation
        val client = direction == NativeRoutingDirection.BackendClient
        val retired = workload in setOf(NativeRoutingWorkload.StaleBackend, NativeRoutingWorkload.AbsentBackend)
        val validSource = skipsRouting().not() && invalidPacket().not() && workload != NativeRoutingWorkload.UnknownSender
        val accepted = validSource && workload != NativeRoutingWorkload.Discovery
        val currentClient = client && retired.not()
        val backendRoute = client.not() && proxy.not() && workload != NativeRoutingWorkload.AbsentBackend
        val expectedClient = if (accepted && currentClient && proxy.not()) packets[index] else emptyList()
        val expectedBackend = if (accepted && backendRoute) packets[index] else emptyList()
        verifyOutput(actor.clientWrites, expectedClient)
        verifyOutput(actor.backendWrites, expectedBackend)
        check(actor.backendDestinations.size == expectedBackend.size)
        actor.backendDestinations.forEach { check(it === actor.current.get()) }
        val inbox = field(peers[index].javaClass, "inbox").get(peers[index]) as RemoteFrameInbox
        val stored = field(inbox.javaClass, "frames").get(inbox) as Collection<*>
        val enqueued = accepted && client.not() && proxy
        check(stored.size == if (enqueued) packets[index].size else 0)
        val processedPlayer = client.not() && workload != NativeRoutingWorkload.UnknownSender && skipsRouting().not()
        check(inbox.failed == (processedPlayer && invalidPacket()))
        val snapshots = stored.map { it as ByteArray }
        snapshots.zip(packets[index]).forEach { (snapshot, bytes) ->
            check(snapshot !== bytes)
            check(snapshot.contentEquals(bytes))
        }
        return mapOf(
            "forwarded_packets" to (expectedClient.size + expectedBackend.size).toLong(),
            "forwarded_bytes" to (expectedClient + expectedBackend).sumOf { it.size.toLong() },
            "owner_inbox_packets" to snapshots.size.toLong(),
            "owner_inbox_snapshot_bytes" to snapshots.sumOf { it.size.toLong() },
        )
    }

    private fun skipsRouting(): Boolean = workload in setOf(NativeRoutingWorkload.WrongChannel, NativeRoutingWorkload.AlreadyHandled, NativeRoutingWorkload.AbsentService)

    private fun invalidPacket(): Boolean = workload in setOf(NativeRoutingWorkload.Malformed, NativeRoutingWorkload.UnknownKind, NativeRoutingWorkload.UnknownEndpoint)

    /**
     * Completes owned work after callback assertions, then releases per-invocation events, peers and outputs.
     */
    fun finish() {
        onOwner { processOwner() }
        field(plugin.javaClass, "screens").set(plugin, service)
        onOwner {
            val disconnected = actors.map { service.disconnect(it.player) }
            ticker.run()
            disconnected.forEach { check(it.isDone && it.isCompletedExceptionally.not()) }
            check((field(host.javaClass, "peers").get(host) as Map<*, *>).isEmpty())
        }
        peers.forEach { peer ->
            val stream = field(peer.javaClass, "stream").get(peer) as RemotePacketStream
            val connection = field(peer.javaClass, "connection").get(peer) as RemoteConnection
            check(field(stream.javaClass, "outgoing").get(stream) == null)
            check(field(connection.javaClass, "outgoing").get(connection) == null)
        }
        events = emptyList()
        results.clear()
        peers = emptyList()
        actors.forEach(NativeRoutingPlayer::clear)
    }

    /**
     * Actual current owned inbox, exposed only to untimed boundary tests and array-identity probes.
     */
    fun inbox(index: Int): RemoteFrameInbox = field(peers[index].javaClass, "inbox").get(peers[index]) as RemoteFrameInbox

    /**
     * Actual queued snapshots before owner processing, inspected only outside timing by the debugger child.
     */
    fun snapshotArrays(): Array<ByteArray> =
        onOwner {
            peers.flatMap { peer ->
                val inbox = field(peer.javaClass, "inbox").get(peer) as RemoteFrameInbox
                val stored = field(inbox.javaClass, "frames").get(inbox) as Collection<*>
                stored.map { it as ByteArray }
            }.toTypedArray()
        }

    override fun close() {
        if (closed) return
        closed = true
        field(plugin.javaClass, "screens").set(plugin, service)
        harness.close()
        check(executor.awaitTermination(10, TimeUnit.SECONDS))
        check((field(host.javaClass, "peers").get(host) as Map<*, *>).isEmpty())
        events = emptyList()
        results.clear()
        peers = emptyList()
        actors.forEach(NativeRoutingPlayer::clear)
    }

    private fun verifyOutput(
        actual: List<ByteArray>,
        expected: List<ByteArray>,
    ) {
        check(actual.size == expected.size)
        actual.indices.forEach { index -> check(actual[index].contentEquals(expected[index])) }
    }

    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
}
