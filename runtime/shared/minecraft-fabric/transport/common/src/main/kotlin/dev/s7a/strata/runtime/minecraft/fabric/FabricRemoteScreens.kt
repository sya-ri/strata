@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteFrameInbox
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteTextCodec
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.Connection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Native custom-payload endpoint shared by all supported Fabric clients.
 * Client extensions register their factories before the first play connection captures [registry].
 * Network callbacks enqueue only owned bytes; all session and rendering work runs on the client tick thread.
 */
public object FabricRemoteScreens {
    public val registry: RemoteRegistry = RemoteRegistry().also(RemoteBuiltins::register)
    private val logger = LoggerFactory.getLogger(FabricRemoteScreens::class.java)
    private val inboxes = ConcurrentHashMap<Connection, RemoteFrameInbox>()
    private val peers = mutableMapOf<RemoteEndpoint, Peer>()
    private var nativeConnection: Connection? = null
    private var failed = false

    @Volatile
    private var stopping: Boolean = false

    /**
     * Receives one authenticated native payload, including a greeting arriving before the first client tick.
     */
    public fun enqueue(
        connection: Connection,
        bytes: ByteArray,
    ) {
        synchronized(inboxes) {
            if (stopping) return
            if (inboxes.size < 2 || inboxes.containsKey(connection)) {
                inboxes.computeIfAbsent(connection) { RemoteFrameInbox() }.offer(bytes)
            }
        }
    }

    /**
     * Releases the transport and screen before native shutdown, without navigating to a replacement screen.
     * Called by the existing render-thread shutdown transaction; cleanup failure is logged after independent queues are released.
     */
    public fun shutdown() {
        val previous = peers.values.toList()
        peers.clear()
        nativeConnection = null
        synchronized(inboxes) {
            stopping = true
            inboxes.values.forEach(RemoteFrameInbox::close)
            inboxes.clear()
        }
        previous.forEach { peer -> runCatching { peer.close(RemoteFailure.Disconnected, false) }.onFailure { logger.warn("Strata remote shutdown failed", it) } }
    }

    /**
     * Reconciles the native connection lifetime and drains bounded protocol work on the client thread.
     */
    public fun tick() {
        if (stopping) return
        val minecraft = Minecraft.getInstance()
        val listener = minecraft.connection
        val native = listener?.connection
        if (listener == null && nativeConnection?.isConnected == true) {
            // Configuration changes replace the play listener while the authenticated transport remains connected.
            peers.values.filter { it.isClosed.not() }.forEach { peer -> guard(peer) { peer.pausePlay() } }
            return
        }
        if (nativeConnection !== native) {
            val previous = peers.values.toList()
            peers.clear()
            previous.forEach { peer -> runCatching { peer.close(RemoteFailure.Disconnected) }.onFailure { logger.warn("Strata remote disconnect failed", it) } }
            nativeConnection = native
            failed = false
            listener?.let { endpoint ->
                FabricRemoteTransport.register(endpoint)
                FabricRemoteTransport.send(endpoint, RemotePacket.encode(RemotePacket.Discovery))
            }
        }
        inboxes.keys.filter { it !== native }.forEach { inboxes.remove(it)?.close() }
        if (native == null || failed) return
        runCatching {
            val inbox = inboxes.computeIfAbsent(native) { RemoteFrameInbox() }
            if (inbox.failed) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Remote receive queue is full.")
            repeat(64) {
                val bytes = inbox.poll() ?: return@repeat
                receiveFrame(native, bytes)
            }
            peers.values
                .toList()
                .filter { it.isClosed.not() }
                .forEach(::tickPeer)
        }.onFailure { failure ->
            failed = true
            peers.values.forEach { peer -> runCatching { peer.close(RemoteFailure.InvalidMessage) }.onFailure(failure::addSuppressed) }
            inboxes.remove(native)?.close()
            logger.warn("Strata remote channel ended", failure)
        }
    }

    private fun tickPeer(peer: Peer) {
        guard(peer) {
            peer.stream.drain(now()) { frame ->
                peer.connection.receive(frame, now())?.let(peer::receive)
                peer.connection.capabilities?.let { peer.stream.limitTo(it.limits) }
            }
            peer.pollScreen()
            peer.connection.tick(now())
            peer.connection.flush()
        }
    }

    private fun receiveFrame(
        native: Connection,
        bytes: ByteArray,
    ) {
        val packet = RemotePacket.decode(bytes)
        require(packet is RemotePacket.Frame) { "Unexpected client-bound discovery." }
        val address = packet.address
        val previous = peers[address.endpoint]
        val peer =
            if (previous?.address == address) {
                previous
            } else {
                previous?.close(RemoteFailure.Disconnected)
                val stream =
                    RemotePacketStream(address) { frame ->
                        val endpoint = checkNotNull(Minecraft.getInstance().connection) { "Native play connection is unavailable." }
                        check(endpoint.connection === native) { "Native connection changed." }
                        FabricRemoteTransport.send(endpoint, frame)
                    }
                val transport = RemoteConnection(registry.types, RemotePacket.limits, stream::send)
                Peer(address, stream, transport, logger) {
                    peers.values.filter { it.address != address }.forEach { it.closeForeground() }
                }.also { peers[address.endpoint] = it }
            }
        if (peer.isClosed.not()) guard(peer) { peer.stream.offer(packet, now()) }
    }

    private inline fun guard(
        peer: Peer,
        operation: () -> Unit,
    ) {
        runCatching(operation).onFailure { failure ->
            val reason = (failure as? RemoteProtocolException)?.reason ?: RemoteFailure.InvalidMessage
            runCatching { peer.close(reason) }.onFailure(failure::addSuppressed)
            logger.warn("Strata remote endpoint ended", failure)
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    /**
     * Contains a native remote-screen failure after the host has performed terminal resource cleanup.
     * Returns false for local screens so their established exception contract remains intact.
     */
    public fun fail(
        view: Screen,
        failure: Throwable,
    ): Boolean {
        val active = peers.values.firstOrNull { it.owns(view) } ?: return false
        val reason = (failure as? RemoteProtocolException)?.reason ?: RemoteFailure.InvalidMessage
        runCatching { active.endScreen(view, reason) }.onFailure { failure.addSuppressed(it) }
        val minecraft = Minecraft.getInstance()
        if (FabricMinecraftScreenAccess.currentScreen(minecraft) === view) FabricMinecraftScreenAccess.setScreen(minecraft, null)
        logger.warn("Strata remote screen ended", failure)
        return true
    }

    /**
     * Authenticated ID routing for an ordinary screen and multiple retained HUD sessions.
     */
    @Suppress("TooManyFunctions") // One authenticated endpoint owns routing, presentation, transport, and terminal cleanup.
    private class Peer(
        val address: RemoteAddress,
        val stream: RemotePacketStream,
        val connection: RemoteConnection,
        private val logger: Logger,
        private val beforeOpen: () -> Unit,
    ) {
        private val sessions = linkedMapOf<Long, Active>()
        private var closed = false
        private var lastSession = 0L
        val isClosed: Boolean get() = closed

        fun owns(view: Screen): Boolean = sessions.values.any { it.screen === view }

        fun endScreen(
            view: Screen,
            reason: RemoteFailure,
        ) {
            sessions.values.find { it.screen === view }?.let { closeSession(it.session.identity, reason, true) }
        }

        fun pausePlay() {
            sessions.values.toList().filter { it.handle.presentation == UiPresentation.Screen || it.screen.boundContainer() != null }.forEach {
                closeSession(it.session.identity, RemoteFailure.ContainerChanged, notify = true, navigate = false)
            }
        }

        fun receive(message: RemoteMessage) {
            if (closed) return
            when (message) {
                is RemoteMessage.Snapshot -> snapshot(message)
                is RemoteMessage.Update -> sessions[message.session]?.session?.receive(message)
                is RemoteMessage.Acknowledgement -> sessions[message.session]?.session?.receive(message)
                is RemoteMessage.ControlReceipt -> sessions[message.session]?.session?.receive(message)
                is RemoteMessage.Control -> sessions[message.session]?.let { applyControl(it, message.state) }
                is RemoteMessage.Close -> closeSession(message.session, message.reason, false)
                else -> throw RemoteProtocolException(RemoteFailure.InvalidMessage, "Unexpected client-bound message.")
            }
            checkNodeBudget()
        }

        fun pollScreen() {
            val client = Minecraft.getInstance()
            sessions.values.toList().forEach { active ->
                val status = active.handle.status
                when {
                    status is UiSessionStatus.Closed -> {
                        closeSession(active.session.identity, RemoteFailure.entries.first { it.uiReason == status.reason }, true)
                    }

                    active.handle.presentation == UiPresentation.Screen && FabricMinecraftScreenAccess.currentScreen(client) !== active.screen -> {
                        closeSession(active.session.identity, RemoteFailure.PeerClosed, true)
                    }

                    active.handle.presentation == UiPresentation.Screen && client.player?.containerMenu !== active.container -> {
                        closeSession(active.session.identity, RemoteFailure.ContainerChanged, true)
                    }

                    else -> {
                        active.session.synchronizeInteraction()
                        active.session.flushEdits()
                    }
                }
            }
        }

        fun close(
            reason: RemoteFailure,
            navigate: Boolean = true,
        ) {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                sessions.keys.toList().forEach { identity ->
                    runCatching { closeSession(identity, reason, false, navigate) }.exceptionOrNull()?.let { caught ->
                        val primary = failure
                        if (primary == null) {
                            failure = caught
                        } else if (primary !== caught) {
                            primary.addSuppressed(caught)
                        }
                    }
                }
            } finally {
                stream.close()
                connection.close()
            }
            failure?.let { throw it }
        }

        private fun snapshot(message: RemoteMessage.Snapshot) {
            sessions[message.session]?.let { active ->
                active.session.receive(message)
                message.control?.let { applyControl(active, it) }
                return
            }
            if (message.session <= lastSession) return
            lastSession = message.session
            val limits = checkNotNull(connection.capabilities).limits
            val presentation = message.control?.presentation ?: message.settings.presentation
            if (presentation == UiPresentation.Hud && limits.hudSessions <= hudCount()) {
                connection.send(RemoteMessage.Close(message.session, RemoteFailure.ResourceLimit))
                return
            }
            if (presentation == UiPresentation.Screen) {
                beforeOpen()
                closeForeground()
            }
            val created = RemoteClientSession(message, registry, limits, connection::send)
            runCatching {
                val handle = FabricUiSessions.open(created.definition(RemoteTextCodec.decode(message.title)), created.uiSession)
                created.bindUiSession(handle)
                val screen = FabricUiSessions.nativeScreen(handle)
                if (screen == null) {
                    created.close(RemoteFailure.PeerClosed)
                    return@runCatching
                }
                val active = Active(created, handle, screen, Minecraft.getInstance().player?.containerMenu)
                sessions[message.session] = active
                message.control?.let { applyControl(active, it) }
            }.onFailure { failure ->
                closeSession(message.session, RemoteFailure.InvalidMessage, true)
                created.close(RemoteFailure.InvalidMessage)
                logger.warn("Strata remote UI could not open", failure)
            }
        }

        private fun applyControl(
            active: Active,
            state: RuntimeUiControl,
        ) {
            if (state.sequence < active.sequence) return
            if (state.sequence == active.sequence) {
                connection.send(RemoteMessage.ControlApplied(active.session.identity, state.sequence, active.rejection))
                return
            }
            val limits = checkNotNull(connection.capabilities).limits
            val rejected = if (state.presentation == UiPresentation.Hud && limits.hudSessions <= hudCount(active.session.identity)) UiRejection.Capacity else null
            if (rejected == null) {
                if (state.presentation == UiPresentation.Screen) {
                    beforeOpen()
                    closeForeground(active.session.identity)
                }
                val controller = active.handle as RuntimeUiController
                controller.transaction {
                    controller.switch(state.presentation)
                    controller.setInputPolicy(state.inputPolicy)
                    controller.setInteractionMode(state.interactionMode)
                }
            }
            val status = active.handle.status
            val reason = rejected ?: (status as? UiSessionStatus.Ready)?.rejection ?: if (status is UiSessionStatus.Closed) UiRejection.Closed else null
            active.sequence = state.sequence
            active.rejection = reason
            active.session.controlApplied(state, reason)
            connection.send(RemoteMessage.ControlApplied(active.session.identity, state.sequence, reason))
        }

        private fun hudCount(except: Long? = null): Int = sessions.values.count { it.session.identity != except && it.handle.presentation == UiPresentation.Hud }

        fun closeForeground(except: Long? = null) {
            sessions.values
                .toList()
                .filter { it.session.identity != except && it.handle.presentation == UiPresentation.Screen }
                .forEach { closeSession(it.session.identity, RemoteFailure.Replaced, true) }
        }

        private fun checkNodeBudget() {
            val limits = connection.capabilities?.limits ?: return
            if (limits.treeNodes < sessions.values.sumOf { it.session.nodeCount }) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Connection node budget exceeded.")
        }

        private fun closeSession(
            identity: Long,
            reason: RemoteFailure,
            notify: Boolean,
            navigate: Boolean = true,
        ) {
            val active = sessions.remove(identity) ?: return
            try {
                connection.discardSession(identity)
                if (navigate) (active.handle as RuntimeUiController).terminate(reason.uiReason) else active.screen.close()
            } finally {
                active.session.close(reason, notify)
            }
        }

        private class Active(
            val session: RemoteClientSession,
            val handle: UiSession,
            val screen: FabricMinecraftScreen,
            val container: Any?,
        ) {
            var sequence = 0L
            var rejection: UiRejection? = null
        }
    }
}
