@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteCanvas
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteFrameInbox
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.remote.RemoteTextCodec
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Integrated-server protocol peer for exact clients that have no official matching Paper distribution.
 * Native packet callbacks only enqueue bounded frames; Minecraft server ticks own every session operation.
 * This test peer proves the client transport and never claims Paper API or Paper server acceptance.
 */
public object RemoteNativeServerFixture {
    private val inboxes = ConcurrentHashMap<ServerPlayer, RemoteFrameInbox>()
    private val peers = mutableMapOf<ServerPlayer, Peer>()

    /**
     * Receives only the installed test packet's authenticated player and detached bounded bytes.
     */
    public fun enqueue(
        player: ServerPlayer,
        bytes: ByteArray,
    ) {
        synchronized(inboxes) {
            check(inboxes.size < 2 || inboxes.containsKey(player))
            inboxes.computeIfAbsent(player) { RemoteFrameInbox() }.offer(bytes)
        }
    }

    /**
     * Advances negotiation and a requested screen on the integrated server's owner thread.
     */
    public fun tick(server: MinecraftServer) {
        val players = server.playerList.players.toSet()
        inboxes.keys.filter { it !in players }.forEach { inboxes.remove(it)?.close() }
        peers.keys.filter { it !in players }.forEach { peers.remove(it)?.close() }
        inboxes.forEach { (player, inbox) ->
            val peer = peers.getOrPut(player) { Peer(player) }
            check(inbox.failed.not())
            repeat(64) { inbox.poll()?.let { peer.receive(it) } }
            peer.tick()
        }
    }

    /**
     * Opens the bounded fixture only after its authenticated native connection has negotiated.
     */
    public fun open(player: ServerPlayer) {
        checkNotNull(peers[player]).open()
    }

    /**
     * Exposes primitive assertions only to the test coordinator on the server thread.
     */
    public fun ready(player: ServerPlayer): Boolean = peers[player]?.connection?.capabilities != null

    /**
     * Reports confirmed business input without exposing authoritative UI state to the client.
     */
    public fun accepted(player: ServerPlayer): Boolean = peers[player]?.let { it.accepted && 0 < (it.screen?.appliedRevision ?: 0) } == true

    /**
     * Reports receipt of the client's terminal notification on the server owner thread.
     */
    public fun closed(player: ServerPlayer): Boolean = peers[player]?.screen?.status == RemoteSessionStatus.Closed(RemoteFailure.PeerClosed)

    /**
     * Releases all references before the fixture's integrated server stops.
     */
    public fun close() {
        synchronized(inboxes) {
            inboxes.values.forEach(RemoteFrameInbox::close)
            inboxes.clear()
        }
        peers.values.forEach(Peer::close)
        peers.clear()
    }

    /**
     * Owns one authenticated native connection and one optional server declaration session.
     */
    private class Peer(
        player: ServerPlayer,
    ) : AutoCloseable {
        private val address = RemoteAddress(RemoteEndpoint.Server)
        private var discovered = false
        private val stream = RemotePacketStream(address) { sendRemoteTestPacket(player, it) }
        val connection = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types + RemoteNativeCanvasFixture.type, RemotePacket.limits, stream::send)
        var screen: RemoteServerSession? = null
        var accepted = false
        private val field = TextFieldState(maxLength = 64)
        private val ticks = mutableStateOf(0)
        private val activated = mutableStateOf(false)

        fun receive(bytes: ByteArray) {
            val packet = RemotePacket.decode(bytes)
            if (packet === RemotePacket.Discovery) {
                if (discovered.not()) {
                    discovered = true
                    connection.start()
                }
                return
            }
            check(packet is RemotePacket.Frame)
            if (packet.address != address || discovered.not()) return
            stream.offer(packet, now())
        }

        private fun receiveFrame(bytes: ByteArray) {
            when (val message = connection.receive(bytes, now())) {
                null -> Unit
                is RemoteMessage.Action -> checkNotNull(screen).receive(message)
                is RemoteMessage.Applied, is RemoteMessage.ControlApplied, is RemoteMessage.ControlRequest -> checkNotNull(screen).receive(message)
                is RemoteMessage.Close -> checkNotNull(screen).close(message.reason, false)
                is RemoteMessage.Resynchronize -> checkNotNull(screen).resynchronize()
                else -> error("Unexpected integrated-server protocol message.")
            }
        }

        fun open() {
            check(screen == null)
            val capabilities = checkNotNull(connection.capabilities)
            val runtime = RemoteComponentRuntime()
            screen =
                RemoteServerSession(1, RemoteTextCodec.encode(UiText.Literal("Strata native protocol verification")), capabilities.types, capabilities.limits, connection::send) {
                    runtime.evaluate {
                        Column(Modifier.Empty.background(ArgbColor(if (activated.value) -16776961 else -14671840))) {
                            TextField(field, IntSize(160, 20))
                            Button(
                                "Confirm",
                                160,
                                modifier =
                                    Modifier.Empty.onActivate {
                                        check(field.value.contentEquals("native-日本語"))
                                        activated.value = true
                                        accepted = true
                                    },
                            )
                            Text("Updates: ${ticks.value}")
                            Canvas(RemoteCanvas.source(RemoteNativeCanvasFixture.type, Unit) { ProjectionValue.Absent }, IntSize(16, 16))
                        }
                    }
                }
        }

        fun tick() {
            if (discovered) {
                stream.drain(now()) { bytes ->
                    receiveFrame(bytes)
                    connection.capabilities?.let { stream.limitTo(it.limits) }
                }
            }
            val session = screen
            if (session != null && session.status !is RemoteSessionStatus.Closed) {
                ticks.value++
                session.tick()
            }
            if (discovered) {
                connection.tick(now())
                connection.flush()
            }
        }

        override fun close() {
            screen?.close(RemoteFailure.Disconnected, false)
            screen = null
            stream.close()
            connection.close()
        }

        private fun now(): Long = System.nanoTime() / 1_000_000
    }
}
