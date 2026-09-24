@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSessionStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns authenticated player transports and declarations under one adapter execution owner.
 * Network callbacks only enqueue bounded bytes; handlers never run in messaging callbacks.
 */
@Suppress("TooManyFunctions") // Keeps the owner, player, transport, and screen lifetime in one service.
public class RemoteScreenService<Player : Any, Owner : Any>(
    private val endpoint: RemoteEndpoint,
    private val send: (Player, ByteArray) -> Unit,
    private val report: (Throwable) -> Unit,
    private val dispatchClose: (() -> Unit) -> Unit = { it() },
    private val notify: (Player, RemoteLifecycleEvent<Owner>) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val owner = RuntimeExecutionOwner.current()
    private val types = RemoteRegistry().also(RemoteBuiltins::register).types
    private val peers = ConcurrentHashMap<Player, Peer<Owner>>()
    private val extensions = mutableMapOf<ProjectionType, Owner>()
    private var nextSession = 1L
    private var dispatching = false
    private var dispatchDepth = 0
    private var transitionCount = 0
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
            peers[player] = Peer(address, stream, connection) { event -> emit(player, event) }
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
        definition: UiDefinition,
    ): RemoteScreenSession {
        checkOwner()
        admitTransition()
        check(nextSession < Long.MAX_VALUE) { "Remote screen identity space is exhausted." }
        val settings = RemoteUiSettings(definition.presentation, definition.category, definition.inputPolicy, definition.visibility, definition.hudOrder)
        val handle = RemoteScreenSession(nextSession++, settings)
        val content = definition.transfer()
        val peer = peers[player]
        val capabilities = peer?.connection?.capabilities
        if (peer == null || capabilities == null) {
            closeUnopened(player, owner, handle, settings.category, RemoteFailure.UnsupportedProtocol)
            return handle
        }
        handle.bind {
            dispatchClose {
                transition {
                    if (peer.sessions[handle.identity]?.handle === handle) {
                        peer.closeSession(handle.identity, RemoteFailure.OwnerClosed)
                    } else {
                        handle.update(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed))
                    }
                }
            }
        }
        transition(admitted = true) {
            if (handle.status is RemoteSessionStatus.Closed) return@transition
            if (peers[player] !== peer) {
                closeUnopened(player, owner, handle, settings.category, RemoteFailure.Disconnected)
                return@transition
            }
            if (settings.presentation == UiPresentation.Hud && capabilities.limits.hudSessions <= peer.hudCount()) {
                closeUnopened(player, owner, handle, settings.category, RemoteFailure.ResourceLimit)
                return@transition
            }
            if (settings.presentation == UiPresentation.Screen) peer.closeForeground(RemoteFailure.Replaced)
            val runtime = RemoteComponentRuntime()
            val session =
                RemoteServerSession(handle.identity, RemoteTextCodec.encode(content.title), capabilities.types.intersect(types + extensions.keys), capabilities.limits, peer.connection::send, content.pausesGame, eventSession = handle.uiSession, controller = handle.controls, settings = settings) {
                    runtime.evaluate(content.content)
                }
            peer.sessions[handle.identity] = Active(owner, handle, session, settings.presentation, settings.category)
            handle.bindControls { request ->
                val active = peer.sessions[handle.identity]
                if (active != null) {
                    if (request.presentation == UiPresentation.Hud && capabilities.limits.hudSessions <= peer.hudCount(handle.identity)) {
                        handle.controls.rejected(request.sequence, UiRejection.Capacity)
                    } else {
                        if (request.presentation == UiPresentation.Screen) peer.closeForeground(RemoteFailure.Replaced, handle.identity)
                        active.presentation = request.presentation
                        session.applyControl(request)
                    }
                }
            }
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
                peer.sessions.values.toList().filter { it.owner === owner || it.session.requiredTypes.any { type -> type in removed } }.forEach { active ->
                    runCatching { peer.closeSession(active.handle.identity, RemoteFailure.OwnerDisabled) }.onFailure(report)
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
        transition {
            peers[player]?.let { peer ->
                peer.sessions.values.toList().filter { it.presentation == UiPresentation.Screen || it.session.usesNativeSlots }.forEach { active ->
                    runCatching { peer.closeSession(active.handle.identity, RemoteFailure.ContainerChanged) }.onFailure(report)
                }
            }
        }
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

        peer.sessions.values
            .toList()
            .forEach { active -> runCatching(active.session::tick).onFailure(report) }
        peer.checkNodeBudget()
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
        val active = peer.sessions[message.session] ?: return
        when (message) {
            is RemoteMessage.Applied, is RemoteMessage.ControlApplied, is RemoteMessage.ControlRequest -> {
                dispatch { runCatching { active.session.receive(message) }.onFailure(report) }
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
                if (message.session == active.handle.identity) peer.closeSession(active.handle.identity, message.reason, false)
            }

            else -> {
                throw RemoteProtocolException(RemoteFailure.InvalidMessage, "Unexpected server-bound message.")
            }
        }
        peer.refresh()
    }

    private fun closeUnopened(
        player: Player,
        owner: Owner,
        handle: RemoteScreenSession,
        category: UiCategory?,
        reason: RemoteFailure,
    ) {
        handle.update(RemoteSessionStatus.Closed(reason))
        emit(player, RemoteLifecycleEvent.Closed(owner, handle.identity, handle.uiSession, null, category, reason.uiReason))
    }

    private fun emit(
        player: Player,
        event: RemoteLifecycleEvent<Owner>,
    ) {
        val operation = {
            runCatching { notify(player, event) }.onFailure(report)
            Unit
        }
        if (dispatching) operation() else dispatch(operation)
    }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() == owner) { "Remote service belongs to another execution owner." }
    }

    private fun admitTransition() {
        if (dispatchDepth != 0 && 64 <= transitionCount++) {
            throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Too many UI transitions in one event.")
        }
    }

    private fun transition(
        admitted: Boolean = false,
        operation: () -> Unit,
    ) {
        checkOwner()
        if (admitted.not()) admitTransition()
        if (dispatching) {
            transitions.addLast(operation)
        } else {
            operation()
        }
    }

    private fun dispatch(operation: () -> Unit) {
        check(dispatching.not()) { "Remote action dispatch cannot reenter." }
        if (dispatchDepth++ == 0) transitionCount = 0
        dispatching = true
        try {
            operation()
        } finally {
            dispatching = false
            try {
                while (transitions.isNotEmpty()) runCatching(transitions.removeFirst()).onFailure(report)
            } finally {
                dispatchDepth--
            }
        }
    }

    /**
     * Compatibility entry sharing the common definition and session path.
     */
    public fun open(
        owner: Owner,
        player: Player,
        definition: ScreenDefinition,
    ): RemoteScreenSession = open(owner, player, definition.asUiDefinition())

    /**
     * Authenticated transport with one ordinary screen and a bounded set of HUDs.
     */
    private class Peer<Owner>(
        val address: RemoteAddress,
        val stream: RemotePacketStream,
        val connection: RemoteConnection,
        private val notify: (RemoteLifecycleEvent<Owner>) -> Unit,
    ) {
        private var ready = false
        var discovered = false
        val inbox = RemoteFrameInbox()
        val sessions = linkedMapOf<Long, Active<Owner>>()

        fun checkNodeBudget() {
            val limits = connection.capabilities?.limits ?: return
            if (limits.treeNodes < sessions.values.sumOf { it.session.nodeCount }) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Connection node budget exceeded.")
        }

        fun hudCount(except: Long? = null): Int = sessions.values.count { it.handle.identity != except && it.presentation == UiPresentation.Hud }

        fun refresh() {
            if (ready.not()) {
                connection.capabilities?.let {
                    ready = true
                    notify(RemoteLifecycleEvent.Ready(it.toUiCapabilities()))
                }
            }
            sessions.values.toList().forEach { active ->
                active.handle.update(active.session.status)
                val status = active.session.status
                val applied = active.handle.uiSession.presentation
                val previous = active.notifiedPresentation
                if (applied != null && previous != applied) {
                    active.notifiedPresentation = applied
                    active.handle.controls.transaction {
                        if (previous == null) {
                            notify(RemoteLifecycleEvent.Opened(active.owner, active.handle.identity, active.handle.uiSession, applied, active.category))
                        } else {
                            notify(RemoteLifecycleEvent.PresentationChanged(active.owner, active.handle.identity, active.handle.uiSession, previous, applied, active.category))
                        }
                    }
                }
                if (active.handle.uiSession.status is UiSessionStatus.Ready) {
                    applied?.let { active.presentation = it }
                }
                if (status is RemoteSessionStatus.Closed && sessions.remove(active.handle.identity) != null) {
                    closed(active, status.reason)
                }
            }
        }

        private fun closed(
            active: Active<Owner>,
            reason: RemoteFailure,
        ) {
            notify(RemoteLifecycleEvent.Closed(active.owner, active.handle.identity, active.handle.uiSession, active.handle.uiSession.presentation, active.category, reason.uiReason))
        }

        fun closeForeground(
            reason: RemoteFailure,
            except: Long? = null,
        ) {
            sessions.values
                .toList()
                .filter { it.presentation == UiPresentation.Screen && it.handle.identity != except }
                .forEach { closeSession(it.handle.identity, reason) }
        }

        fun closeSession(
            identity: Long,
            reason: RemoteFailure,
            notify: Boolean = true,
        ) {
            val active = sessions.remove(identity) ?: return
            active.handle.update(RemoteSessionStatus.Closed(reason))
            try {
                connection.discardSession(identity)
                active.session.close(reason, notify)
            } finally {
                active.handle.update(active.session.status)
                closed(active, reason)
            }
        }

        fun close(reason: RemoteFailure) {
            var failure: Throwable? = null
            try {
                sessions.keys.toList().forEach { identity ->
                    runCatching { closeSession(identity, reason, false) }.exceptionOrNull()?.let { caught ->
                        val primary = failure
                        if (primary == null) {
                            failure = caught
                        } else if (primary !== caught) {
                            primary.addSuppressed(caught)
                        }
                    }
                }
            } finally {
                inbox.close()
                stream.close()
                connection.close()
                if (ready) {
                    ready = false
                    notify(RemoteLifecycleEvent.Disconnected(reason.uiReason))
                }
            }
            failure?.let { throw it }
        }
    }

    /**
     * Plugin ownership released with its public handle and retained server tree.
     */
    private data class Active<Owner>(
        val owner: Owner,
        val handle: RemoteScreenSession,
        val session: RemoteServerSession,
        var presentation: UiPresentation,
        val category: UiCategory?,
        var notifiedPresentation: UiPresentation? = null,
    )
}
