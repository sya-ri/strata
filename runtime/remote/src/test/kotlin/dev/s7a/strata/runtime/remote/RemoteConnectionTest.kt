package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies negotiation, queue limits, and failure cleanup without a game transport.
 */
internal class RemoteConnectionTest {
    @Test
    fun terminalSessionReleasesQueuedAndPartiallyDeliveredMessagesWithoutBreakingTheConnection() {
        val frames = ArrayDeque<ByteArray>()
        val bounds = RemoteLimits(frameBytes = 256, reconstructionMillis = 60_000)
        val sender = RemoteConnection(emptySet(), bounds, frames::addLast)
        val receiver = RemoteConnection(emptySet(), bounds, send = { sender.receive(it, 0) })
        receiver.start()
        receiver.flush()
        sender.flush()
        while (frames.isNotEmpty()) receiver.receive(frames.removeFirst(), 0)
        sender.send(RemoteMessage.Action(1, 1, 1, BuiltinProjection.PrimaryPress.type, ProjectionValue.Text("x".repeat(4000))))
        sender.flush(1)
        assertNull(receiver.receive(frames.removeFirst(), 1))
        sender.send(RemoteMessage.Action(1, 2, 1, BuiltinProjection.PrimaryPress.type, ProjectionValue.Absent))
        sender.send(RemoteMessage.Close(1, RemoteFailure.Replaced))
        sender.send(RemoteMessage.Close(2, RemoteFailure.OwnerClosed))
        sender.flush(100)
        val messages = frames.mapNotNull { receiver.receive(it, 2) }
        assertEquals(listOf(RemoteMessage.Close(1, RemoteFailure.Replaced), RemoteMessage.Close(2, RemoteFailure.OwnerClosed)), messages)
        sender.close()
        receiver.close()
    }

    @Test
    fun silentPeerExpiresNegotiationAndReleasesQueuedGreeting() {
        val frames = mutableListOf<ByteArray>()
        val connection = RemoteConnection(emptySet(), RemoteLimits(assemblyMillis = 10), frames::add)
        connection.start()
        connection.tick(100)
        assertEquals(RemoteFailure.TimedOut, assertThrows(RemoteProtocolException::class.java) { connection.tick(111) }.reason)
        assertThrows(IllegalStateException::class.java) { connection.flush() }
        assertEquals(0, frames.size)
    }

    @Test
    fun negotiatesExactSchemasAndLimitsBeforeApplicationMessages() {
        val toClient = ArrayDeque<ByteArray>()
        val toServer = ArrayDeque<ByteArray>()
        // Negotiation assertions are independent of instrumented class-loading time; deadline tests use an injected clock.
        val limits = RemoteLimits(reconstructionMillis = 60_000)
        val server = RemoteConnection(setOf(BuiltinProjection.Row.type, BuiltinProjection.Column.type), limits, toClient::addLast)
        val client = RemoteConnection(setOf(BuiltinProjection.Row.type), limits.copy(frameBytes = 1024), toServer::addLast)
        try {
            server.start()
            assertNull(server.capabilities)
            server.flush()
            toClient.forEach { assertNull(client.receive(it, 0)) }
            toClient.clear()
            client.flush()
            toServer.forEach { assertNull(server.receive(it, 0)) }
            assertEquals(1024, server.capabilities?.limits?.frameBytes)
            assertEquals(setOf(BuiltinProjection.Row.type), server.capabilities?.types)
            server.send(RemoteMessage.Close(1, RemoteFailure.OwnerClosed))
            server.flush()
            assertEquals(RemoteMessage.Close(1, RemoteFailure.OwnerClosed), client.receive(toClient.single(), 0))
        } finally {
            server.close()
            client.close()
        }
    }

    @Test
    fun rejectsPrematureDataAndReleasesTransportAfterFailure() {
        val frames = mutableListOf<ByteArray>()
        RemoteFraming().send(RemoteMessageCodec().encode(RemoteMessage.Resynchronize(1)), frames::add)
        val connection = RemoteConnection(emptySet(), send = { error("No output expected.") })
        assertThrows(IllegalArgumentException::class.java) { connection.receive(frames.single(), 0) }
        assertThrows(IllegalStateException::class.java) { connection.flush() }
        assertNull(connection.capabilities)
    }

    @Test
    fun aFullQueueFailsExplicitlyAndCannotDeliverPartialActions() {
        val frames = ArrayDeque<ByteArray>()
        val bounds = RemoteLimits(frameBytes = 128, messageBytes = 256, collectionEntries = 32, treeNodes = 8, pendingBytes = 256)
        val connection = RemoteConnection(emptySet(), bounds, frames::addLast)
        val peer = RemoteConnection(emptySet(), bounds, send = { connection.receive(it, 0) })
        peer.start()
        peer.flush()
        val failure =
            assertThrows(RemoteProtocolException::class.java) {
                repeat(100) { connection.send(RemoteMessage.Resynchronize(1)) }
            }
        assertEquals(RemoteFailure.ResourceLimit, failure.reason)
        assertThrows(IllegalStateException::class.java) { connection.flush() }
        assertEquals(0, frames.size)
        peer.close()
    }
}
