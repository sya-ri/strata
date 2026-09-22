@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns authenticated player transports and declarations on one adapter-owned thread.
 * Network callbacks only enqueue bounded bytes; handlers never run in messaging callbacks.
 */
@Suppress("TooManyFunctions") // Keeps the owner, player, transport, and screen lifetime in one service.
public class RemoteScreenService<Player : Any, Owner : Any>(
    private val endpoint: RemoteEndpoint,
    private val send: (Player, ByteArray) -> Unit,
    private val report: (Throwable) -> Unit,
    private val dispatchClose: (() -> Unit) -> Unit = { it() },
) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private val types = RemoteRegistry().also(RemoteBuiltins::register).types
    private val peers = ConcurrentHashMap<Player, Peer<Owner>>()
    private val extensions = mutableMapOf<ProjectionType, Owner>()
    private var nextSession = 1L
    private var dispatching = false
    private val transitions = ArrayDeque<() -> Unit>()

    /**
     * Creates a fresh protocol endpoint for a joined player.
     */
    public fun join(player: Player) {
        transition {
            disconnect(player)
            val address = RemoteAddress(endpoint)
            val stream = RemotePacketStream(address) { bytes -> send(player, bytes) }
            val connection = RemoteConnection(types + extensions.keys, RemotePacket.limits, stream::send)
            peers[player] = Peer(address, stream, connection)
        }
    }

    /**
     * Copies inbound authenticated payloads without executing server-owned behavior.
     */
    public fun enqueue(
        player: Player,
        bytes: ByteArray,
    ) {
        peers[player]?.inbox?.offer(bytes)
    }

    /**
     * Returns the successful capability handshake for one current player connection.
     */
    public fun capabilities(player: Player): RemoteCapabilities? {
        checkOwner()
        return peers[player]?.connection?.capabilities
    }

    /**
     * Admits one exact plugin-owned schema for subsequent connections.
     */
    public fun register(
        owner: Owner,
        type: ProjectionType,
    ) {
        checkOwner()
        require((type in types).not() && (type in extensions).not()) { "A remote schema is already registered: $type." }
        extensions[type] = owner
    }

    /**
     * Consumes the definition and opens a screen only after successful peer negotiation.
     */
    public fun open(
        owner: Owner,
        player: Player,
        definition: ScreenDefinition,
    ): RemoteScreenSession {
        checkOwner()
        check(nextSession < Long.MAX_VALUE) { "Remote screen identity space is exhausted." }
        val handle = RemoteScreenSession(nextSession++)
        val content = definition.transfer()
        val peer = peers[player]
        val capabilities = peer?.connection?.capabilities
        if (peer == null || capabilities == null) {
            handle.update(RemoteSessionStatus.Closed(RemoteFailure.UnsupportedProtocol))
            return handle
        }
        handle.bind {
            dispatchClose {
                transition {
                    if (peer.screen?.handle === handle) {
                        peer.closeScreen(RemoteFailure.OwnerClosed)
                    } else {
                        handle.update(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed))
                    }
                }
            }
        }
        transition {
            if (handle.status is RemoteSessionStatus.Closed) return@transition
            if (peers[player] !== peer) {
                handle.update(RemoteSessionStatus.Closed(RemoteFailure.Disconnected))
                return@transition
            }
            peer.closeScreen(RemoteFailure.Replaced)
            val runtime = RemoteComponentRuntime()
            val session =
                RemoteServerSession(handle.identity, RemoteTextCodec.encode(content.title), capabilities.types.intersect(types + extensions.keys), capabilities.limits, peer.connection::send, content.pausesGame) {
                    runtime.evaluate(content.content)
                }
            peer.screen = Active(owner, handle, session)
            runCatching(session::tick).onFailure { failure -> report(failure) }
            peer.refresh()
        }
        return handle
    }

    /**
     * Processes a bounded number of frames, then commits state and flushes bounded output for each player.
     */
    public fun tick() {
        checkOwner()
        val now = System.nanoTime() / 1_000_000
        peers.entries.toList().forEach { (player, peer) ->
            runCatching { tick(peer, now) }.onFailure { failure ->
                peers.remove(player, peer)
                val reason = (failure as? RemoteProtocolException)?.reason ?: RemoteFailure.InvalidMessage
                runCatching { peer.close(reason) }.onFailure(report)
                report(failure)
            }
        }
    }

    /**
     * Ends every screen owned by a plugin before that plugin can be unloaded.
     */
    public fun ownerDisabled(owner: Owner) {
        checkOwner()
        val removed = extensions.filterValues { it === owner }.keys.toSet()
        removed.forEach(extensions::remove)
        transition {
            peers.values.forEach { peer ->
                val screen = peer.screen
                if (screen != null && (screen.owner === owner || screen.session.requiredTypes.any { it in removed })) {
                    runCatching { peer.closeScreen(RemoteFailure.OwnerClosed) }.onFailure(report)
                }
            }
        }
    }

    /**
     * Removes all queues and state belonging to one disconnected player.
     */
    public fun disconnect(player: Player) {
        transition { peers.remove(player)?.let { runCatching { it.close(RemoteFailure.Disconnected) }.onFailure(report) } }
    }

    /**
     * Invalidates the current screen generation when Minecraft replaces or closes its native container.
     * Subsequent messages still carry the retired session identity and cannot target a replacement screen.
     */
    public fun containerChanged(player: Player) {
        transition { peers[player]?.let { runCatching { it.closeScreen(RemoteFailure.ContainerChanged) }.onFailure(report) } }
    }

    override fun close() {
        transition {
            val remaining = peers.values.toList()
            peers.clear()
            extensions.clear()
            remaining.forEach { runCatching { it.close(RemoteFailure.OwnerClosed) }.onFailure(report) }
        }
    }

    private fun tick(
        peer: Peer<Owner>,
        now: Long,
    ) {
        if (peer.inbox.failed) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Remote receive queue is full.")
        repeat(64) {
            val bytes = peer.inbox.poll() ?: return@repeat
            when (val packet = RemotePacket.decode(bytes)) {
                RemotePacket.Discovery -> {
                    if (peer.discovered.not()) {
                        peer.discovered = true
                        peer.connection.start()
                    }
                }

                is RemotePacket.Frame -> {
                    if (packet.address == peer.address && peer.discovered) {
                        peer.stream.offer(packet, now)
                    }
                }
            }
        }
        if (peer.discovered) {
            peer.stream.drain(now) { bytes ->
                peer.connection.receive(bytes, now)?.let { receive(peer, it) }
                peer.connection.capabilities?.let { peer.stream.limitTo(it.limits) }
            }
        }
        peer.screen?.session?.let { session -> runCatching(session::tick).onFailure(report) }
        peer.refresh()
        if (peer.discovered) {
            peer.connection.tick(now)
            peer.connection.flush()
        }
    }

    private fun receive(
        peer: Peer<Owner>,
        message: RemoteMessage,
    ) {
        val active = peer.screen ?: return
        when (message) {
            is RemoteMessage.Applied -> {
                if (message.session == active.handle.identity) runCatching { active.session.receive(message) }.onFailure(report)
            }

            is RemoteMessage.Action -> {
                if (message.session == active.handle.identity) {
                    dispatch {
                        runCatching { active.session.receive(message) }.onFailure(report)
                    }
                }
            }

            is RemoteMessage.Resynchronize -> {
                if (message.session == active.handle.identity) active.session.resynchronize()
            }

            is RemoteMessage.Close -> {
                if (message.session == active.handle.identity) peer.closeScreen(message.reason, false)
            }

            else -> {
                throw RemoteProtocolException(RemoteFailure.InvalidMessage, "Unexpected server-bound message.")
            }
        }
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "Remote service belongs to another thread." }
    }

    private fun transition(operation: () -> Unit) {
        checkOwner()
        if (dispatching) {
            if (64 <= transitions.size) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Too many screen transitions in one action.")
            transitions.addLast(operation)
        } else {
            operation()
        }
    }

    private fun dispatch(operation: () -> Unit) {
        check(dispatching.not()) { "Remote action dispatch cannot reenter." }
        dispatching = true
        try {
            operation()
        } finally {
            dispatching = false
            while (transitions.isNotEmpty()) runCatching(transitions.removeFirst()).onFailure(report)
        }
    }

    /**
     * Authenticated transport and its current optional owner screen.
     */
    private class Peer<Owner>(
        val address: RemoteAddress,
        val stream: RemotePacketStream,
        val connection: RemoteConnection,
    ) {
        val inbox = RemoteFrameInbox()
        var discovered = false
        var screen: Active<Owner>? = null

        fun refresh() {
            val active = screen ?: return
            active.handle.update(active.session.status)
            if (active.session.status is RemoteSessionStatus.Closed) screen = null
        }

        fun closeScreen(
            reason: RemoteFailure,
            notify: Boolean = true,
        ) {
            val active = screen ?: return
            screen = null
            try {
                connection.discardSession(active.handle.identity)
                active.session.close(reason, notify)
            } finally {
                active.handle.update(active.session.status)
            }
        }

        fun close(reason: RemoteFailure) {
            try {
                closeScreen(reason, false)
            } finally {
                inbox.close()
                stream.close()
                connection.close()
            }
        }
    }

    /**
     * References released together when a screen ends or is replaced.
     */
    private data class Active<Owner>(
        val owner: Owner,
        val handle: RemoteScreenSession,
        val session: RemoteServerSession,
    )
}
