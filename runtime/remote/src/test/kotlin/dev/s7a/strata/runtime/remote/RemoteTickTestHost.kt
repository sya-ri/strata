@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation

/**
 * Real negotiated test connection with callback installation before Ready and explicit fixed tick phases.
 * Public handles and decoded protocol output provide independent oracles without injecting private membership.
 */
internal class RemoteTickTestHost : AutoCloseable {
    val events = mutableListOf<RemoteLifecycleEvent<Any>>()
    val failures = mutableListOf<Throwable>()
    val handles = mutableListOf<RemoteScreenSession>()
    var listener: (RemoteLifecycleEvent<Any>) -> Unit = {}
    var reporter: (Throwable) -> Unit = failures::add
    var sender: (ByteArray) -> Unit = { outgoing.addLast(it) }
    private val outgoing = ArrayDeque<ByteArray>()
    private val incoming = ArrayDeque<ByteArray>()
    private var stream: RemotePacketStream? = null
    val service =
        RemoteScreenService<Unit, Any>(RemoteEndpoint.Server, { _, bytes -> sender(bytes) }, { reporter(it) }, notify = { _, event ->
            events += event
            listener(event)
        })
    val client = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, RemotePacket.limits) { checkNotNull(stream).send(it) }

    /**
     * Joins/discovers and delivers the actual greeting before the separate negotiation tick.
     */
    fun discover() {
        service.join(Unit)
        service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
        service.tick()
        drain()
        flush()
    }

    /**
     * Completes the second real handshake tick, including Ready callback attempts.
     */
    fun negotiate() {
        discover()
        service.tick()
    }

    /**
     * Opens one real current definition using its public UI controls.
     */
    fun open(
        presentation: UiPresentation = UiPresentation.Hud,
        owner: Any = Any(),
    ): RemoteScreenSession = service.open(owner, Unit, UiDefinition(presentation = presentation) { Spacer() }).also(handles::add)

    /**
     * Opens the supplied actual definition while retaining its public handle for terminal assertions.
     */
    fun open(
        definition: UiDefinition,
        owner: Any = Any(),
    ): RemoteScreenSession = service.open(owner, Unit, definition).also(handles::add)

    /**
     * Drains current real packets without advancing the service or substituting a fake session.
     */
    fun drain(): List<RemoteMessage> {
        while (outgoing.isNotEmpty()) {
            val packet = RemotePacket.decode(outgoing.removeFirst()) as RemotePacket.Frame
            val current = stream ?: RemotePacketStream(packet.address) { incoming.addLast(it) }.also { stream = it }
            check(packet.address == current.address)
            current.offer(packet, 0)
        }
        return buildList { stream?.drain(0) { client.receive(it, 0)?.let(::add) } }
    }

    /**
     * Sends exact control/declaration acknowledgements for each actually decoded snapshot.
     * Fixed passes follow the eight-frame production output budget, with two client flushes per delivery.
     */
    fun acknowledge() {
        val deliveryTicks = maxOf(1, (handles.count { (it.status is RemoteSessionStatus.Closed).not() } + 7) / 8)
        repeat(deliveryTicks) {
            service.tick()
            drain().filterIsInstance<RemoteMessage.Snapshot>().forEach { snapshot ->
                client.send(RemoteMessage.Applied(snapshot.session, snapshot.revision))
                snapshot.control?.let { client.send(RemoteMessage.ControlApplied(snapshot.session, it.sequence)) }
            }
            client.flush()
            flush()
        }
        service.tick()
    }

    /**
     * Enqueues already generated authenticated frames, preserving ordinary defensive copies.
     */
    fun flush() {
        client.flush()
        while (incoming.isNotEmpty()) service.enqueue(Unit, incoming.removeFirst())
    }

    override fun close() {
        listener = {}
        reporter = failures::add
        sender = { outgoing.addLast(it) }
        try {
            service.close()
        } finally {
            client.close()
            stream?.close()
        }
    }
}
