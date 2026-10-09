@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSessionStatus

/**
 * Actual negotiated public-route service fixture; one source belongs to each independent UI owner.
 * Six lifecycle phases and five distinct-peer churn phases use fixed topology-specific delivery tick counts.
 * Current client trees/queues are bounded by the fleet; completed operations retain no output history.
 */
@Suppress("TooManyFunctions") // Keeps the frozen public-route tick phases and their current owners in one fixture.
internal class RemoteServiceFleet(
    val topology: RemoteServiceShape,
) : AutoCloseable {
    /**
     * Adapts a generated timed enum to its exact real fleet input.
     */
    constructor(topology: RemoteServiceTopology) : this(RemoteServiceShape(topology.peers, topology.huds, topology.screens))

    val peers = linkedMapOf<Int, RemoteServicePeer>()
    val sources = linkedMapOf<Long, RemoteStateSource<Int>>()
    val handles = linkedMapOf<Long, RemoteScreenSession>()
    var listener: (Int, RemoteLifecycleEvent<Any>) -> Unit = { _, _ -> }
    var reporter: (Throwable) -> Unit = { throw it }
    var sender: (Int, ByteArray) -> Unit = { player, bytes -> checkNotNull(peers[player]).outbound.addLast(bytes) }
    var peerJoined: (RemoteServicePeer) -> Unit = {}
    var phaseInspector: (() -> Unit)? = null
    var packetInspector: ((Int, Direction, RemotePacket) -> Unit)? = null
    val service =
        RemoteScreenService<Int, Any>(RemoteEndpoint.Server, { player, bytes ->
            sender(player, bytes)
            packetInspector?.invoke(player, Direction.ToClient, RemotePacket.decode(bytes))
        }, { reporter(it) }, notify = { player, event -> listener(player, event) })
    var ticks: Long = 0
        private set
    var publications: Long = 0
        private set
    var evaluations: Long = 0
        private set
    val subscriptions: Int get() = sources.values.sumOf { it.subscriptions }
    val nodeCount: Int get() = peers.values.sumOf { peer -> peer.trees.values.sumOf { it.nodes.size } }

    /**
     * Constructs/negotiates the initial population, opens HUDs before screens, and acknowledges applied owners.
     * Discovery, negotiation, bounded delivery and final application are fixed before collection.
     * Delivery uses one, two or three complete ticks for at most eight, sixteen or seventeen sessions.
     */
    fun establish(
        verifyApplied: Boolean = true,
        deliveryTicks: Int = topology.deliveryTicks,
    ) {
        repeat(topology.peers) { join(it) }
        tick()
        deliver()
        flushIncoming()
        tick()
        check(peers.keys.all { service.capabilities(it) != null })
        peers.keys.toList().forEach(::openPeer)
        repeat(deliveryTicks) {
            tick()
            deliver()
            acknowledge()
        }
        tick()
        deliver()
        if (verifyApplied) check(handles.values.all { it.uiSession.status is UiSessionStatus.Ready })
    }

    /**
     * One complete quiet tick, with unchanged service/transport/session work.
     */
    fun idle(): Int {
        tick()
        return peers.size
    }

    /**
     * Publishes to one current owner and includes real bounded output decoding.
     */
    fun oneSource(): Int {
        val source = sources.values.first()
        source.publish(source.value + 1)
        publications++
        tick()
        deliver()
        discardMessages()
        return peers.size
    }

    /**
     * Publishes independently to every current source and includes every fixed bounded output-delivery tick.
     */
    fun allSources(): Int {
        sources.values.forEach { it.publish(it.value + 1) }
        publications += sources.size
        repeat(topology.deliveryTicks) {
            tick()
            deliver()
            discardMessages()
        }
        return peers.size
    }

    /**
     * Joins one additional distinct peer, negotiates, opens/acknowledges it, disconnects, and closes its client.
     * Initial owners remain active; the actual peak is P+1 peers and (P+1)*(H+F) sessions.
     */
    fun churn(): Int {
        val player = topology.peers
        join(player)
        tick()
        deliver()
        flushIncoming()
        tick()
        checkNotNull(service.capabilities(player))
        val admitted = openPeer(player)
        repeat(topology.deliveryTicks) {
            tick()
            deliver()
            acknowledge()
        }
        tick()
        deliver()
        check(admitted.all { it.uiSession.status is UiSessionStatus.Ready })
        service.disconnect(player)
        tick()
        deliver()
        check(admitted.all { it.status is RemoteSessionStatus.Closed })
        admitted.forEach {
            check(checkNotNull(sources.remove(it.identity)).subscriptions == 0)
            handles.remove(it.identity)
        }
        checkNotNull(peers.remove(player)).close()
        discardMessages()
        return peers.size
    }

    /**
     * Completes source/update phase five and update acknowledgement phase six, including explicit empty controls.
     */
    fun finishLifecycle(): Int {
        if (sources.isNotEmpty()) {
            sources.values.first().let { it.publish(it.value + 1) }
            publications++
        }
        tick()
        deliver()
        acknowledge()
        tick()
        deliver()
        return peers.size
    }

    /**
     * Admits one real discovery without pre-negotiating its transport.
     */
    fun join(
        player: Int,
        limits: RemoteLimits = RemotePacket.limits,
    ) {
        peers[player] = RemoteServicePeer(player, limits)
        peerJoined(checkNotNull(peers[player]))
        service.join(player)
        enqueue(player, RemotePacket.encode(RemotePacket.Discovery))
    }

    /**
     * Opens independent actual HUD definitions before the single foreground definition.
     */
    fun openPeer(player: Int): List<RemoteScreenSession> =
        buildList {
            repeat(topology.huds) { add(open(player, UiPresentation.Hud)) }
            repeat(topology.screens) { add(open(player, UiPresentation.Screen)) }
        }

    /**
     * Uses a real observed declaration; content attempts can be installed by independent gates.
     */
    fun open(
        player: Int,
        presentation: UiPresentation,
        owner: Any = Any(),
        released: () -> Unit = {},
        content: () -> Unit = {},
    ): RemoteScreenSession {
        val source = RemoteStateSource(0, released)
        return open(player, owner, source, definition(presentation, source, null, content))
    }

    /**
     * Opens the same observed declaration with one real pointer action for duplicate/stale-input gates.
     */
    fun openAction(
        player: Int,
        presentation: UiPresentation,
        action: () -> Unit,
    ): RemoteScreenSession {
        val source = RemoteStateSource(0)
        return open(player, Any(), source, definition(presentation, source, action, {}))
    }

    private fun open(
        player: Int,
        owner: Any,
        source: RemoteStateSource<Int>,
        definition: UiDefinition,
    ): RemoteScreenSession {
        val handle = service.open(owner, player, definition)
        sources[handle.identity] = source
        handles[handle.identity] = handle
        phaseInspector?.invoke()
        return handle
    }

    private fun definition(
        presentation: UiPresentation,
        source: RemoteStateSource<Int>,
        action: (() -> Unit)?,
        content: () -> Unit,
    ): UiDefinition =
        UiDefinition(presentation = presentation) {
            Observe(source) { value ->
                content()
                evaluations++
                Column {
                    val background = Modifier.Empty.background(ArgbColor(if (value % 2 == 0) -65536 else -16776961))
                    Spacer(if (action == null) background else background.onActivate { action() })
                }
            }
        }

    /**
     * Executes exactly one complete production tick, without readiness retries.
     */
    fun tick() {
        phaseInspector?.invoke()
        service.tick()
        ticks++
    }

    /**
     * Consumes every current peer's bounded real output once.
     */
    fun deliver() = peers.values.forEach(RemoteServicePeer::deliver)

    /**
     * Flushes already queued client messages into the service's defensive network enqueue boundary.
     */
    fun flushIncoming() {
        peers.values.forEach { peer ->
            peer.connection.flush()
            while (peer.inbound.isNotEmpty()) enqueue(peer.player, peer.inbound.removeFirst())
        }
    }

    /**
     * Executes the real defensive enqueue boundary and optionally records the actual authenticated packet attempt.
     * Untimed packet inspection is absent in JMH operations and never substitutes for server admission.
     */
    fun enqueue(
        player: Int,
        bytes: ByteArray,
    ) {
        service.enqueue(player, bytes)
        packetInspector?.invoke(player, Direction.ToServer, RemotePacket.decode(bytes))
    }

    /**
     * Sends actual control/declaration acknowledgements and enqueues them before the next fixed tick.
     */
    fun acknowledge() {
        peers.values.forEach(RemoteServicePeer::acknowledge)
        flushIncoming()
    }

    /**
     * Drops phase-local messages after current trees and revisions have already been validated.
     */
    fun discardMessages() = peers.values.forEach { it.messages.clear() }

    /**
     * Verifies terminal release with public handles deliberately kept reachable.
     */
    fun verifyClosed() {
        check(subscriptions == 0)
        check(handles.values.all { it.status is RemoteSessionStatus.Closed && it.uiSession.status is UiSessionStatus.Closed })
        check(peers.values.all { it.outbound.isEmpty() && it.inbound.isEmpty() && it.trees.isEmpty() })
    }

    override fun close() {
        try {
            service.close()
        } finally {
            peers.values.forEach(RemoteServicePeer::close)
        }
    }

    /**
     * Actual protocol delivery direction, decoded only at the fixture's observation boundary.
     */
    enum class Direction {
        ToClient,
        ToServer,
    }
}
