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
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.Connection
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
                Peer(address, stream, transport).also { peers[address.endpoint] = it }
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
     * Verifies the captured native container generation immediately before a Slot read or transaction.
     * Called at the existing versioned inventory boundary; vanilla remains authoritative for item movement.
     */
    public fun requireContainer(menu: Any) {
        peers.values.forEach { it.requireContainer(menu) }
    }

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
        runCatching { active.endScreen(reason) }.onFailure { failure.addSuppressed(it) }
        val minecraft = Minecraft.getInstance()
        if (FabricMinecraftScreenAccess.currentScreen(minecraft) === view) FabricMinecraftScreenAccess.setScreen(minecraft, null)
        logger.warn("Strata remote screen ended", failure)
        return true
    }

    /**
     * Owns exactly one transport and at most one visible remote screen.
     */
    private class Peer(
        val address: RemoteAddress,
        val stream: RemotePacketStream,
        val connection: RemoteConnection,
    ) {
        private var session: RemoteClientSession? = null
        private var screen: Screen? = null
        private var container: Any? = null
        private var closed = false
        private var lastSession = 0L
        val isClosed: Boolean get() = closed

        fun owns(view: Screen): Boolean = session != null && (screen === view || (screen == null && FabricMinecraftScreenAccess.currentScreen(Minecraft.getInstance()) === view))

        fun requireContainer(menu: Any) {
            if (session == null) return
            val current = FabricMinecraftScreenAccess.currentScreen(Minecraft.getInstance())
            if ((screen == null || current === screen) && container !== menu) throw RemoteProtocolException(RemoteFailure.ContainerChanged, "Remote screen container generation changed.")
        }

        fun endScreen(reason: RemoteFailure) {
            closeScreen(reason, true)
        }

        fun pausePlay() {
            closeScreen(RemoteFailure.ContainerChanged, true, false)
        }

        fun receive(message: RemoteMessage) {
            if (closed) return
            when (message) {
                is RemoteMessage.Snapshot -> snapshot(message)
                is RemoteMessage.Update -> session?.takeIf { it.identity == message.session }?.receive(message)
                is RemoteMessage.Acknowledgement -> session?.takeIf { it.identity == message.session }?.receive(message)
                is RemoteMessage.Close -> if (session?.identity == message.session) closeScreen(message.reason, false)
                else -> throw RemoteProtocolException(RemoteFailure.InvalidMessage, "Unexpected client-bound message.")
            }
        }

        fun pollScreen() {
            if (session != null && FabricMinecraftScreenAccess.currentScreen(Minecraft.getInstance()) !== screen) {
                closeScreen(RemoteFailure.PeerClosed, true)
            }
            if (session != null && Minecraft.getInstance().player?.containerMenu !== container) closeScreen(RemoteFailure.ContainerChanged, true)
            session?.flushEdits()
        }

        fun close(
            reason: RemoteFailure,
            navigate: Boolean = true,
        ) {
            if (closed) return
            closed = true
            stream.use {
                connection.use { closeScreen(reason, false, navigate) }
            }
        }

        private fun snapshot(message: RemoteMessage.Snapshot) {
            val existing = session
            if (existing?.identity == message.session) {
                existing.receive(message)
                return
            }
            if (message.session <= lastSession) return
            lastSession = message.session
            closeScreen(RemoteFailure.Replaced, false)
            peers.values.filter { it !== this }.forEach { it.endScreen(RemoteFailure.Replaced) }
            val limits = checkNotNull(connection.capabilities).limits
            val created = RemoteClientSession(message, registry, limits, connection::send)
            session = created
            container = Minecraft.getInstance().player?.containerMenu
            runCatching {
                created.definition(RemoteTextCodec.decode(message.title)).open()
                if (session === created) screen = FabricMinecraftScreenAccess.currentScreen(Minecraft.getInstance())
            }.onFailure { failure ->
                closeScreen((failure as? RemoteProtocolException)?.reason ?: RemoteFailure.InvalidMessage, true)
                LoggerFactory.getLogger(FabricRemoteScreens::class.java).warn("Strata remote screen could not open", failure)
            }
        }

        private fun closeScreen(
            reason: RemoteFailure,
            notify: Boolean,
            navigate: Boolean = true,
        ) {
            val previous = session
            val view = screen
            session = null
            screen = null
            container = null
            try {
                previous?.let { connection.discardSession(it.identity) }
                val minecraft = Minecraft.getInstance()
                if (navigate && view != null && FabricMinecraftScreenAccess.currentScreen(minecraft) === view) view.onClose()
                (view as? AutoCloseable)?.close()
            } finally {
                previous?.close(reason, notify)
            }
        }
    }
}
