package dev.s7a.strata.runtime.remote

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.TreeMap

/**
 * Exercises the real service ingress before ordered logical delivery, including authentication and terminal cleanup.
 * The private phase call excludes unrelated message interpretation while inspecting actual peer-owned storage.
 */
internal class RemotePacketIngressTest {
    @Test
    fun actualServerIngressRequiresDiscoveryAndExactCurrentIncarnation() {
        val failures = mutableListOf<Throwable>()
        RemoteScreenService<Unit, Unit>(RemoteEndpoint.Server, { _, _ -> }, failures::add).use { service ->
            service.join(Unit)
            val peer = peer(service)
            val address = field(peer, "address") as RemoteAddress
            val stream = field(peer, "stream") as RemotePacketStream
            val expected = ByteArray(RemotePacket.limits.frameBytes) { (it * 31).toByte() }
            val native = RemotePacket.encode(RemotePacket.Frame(address, 2, expected))
            service.enqueue(Unit, native)
            receive(service, peer)
            assertTrue(pending(stream).isEmpty())
            service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            service.enqueue(Unit, RemotePacket.encode(RemotePacket.Frame(RemoteAddress(RemoteEndpoint.Server), 1, expected)))
            service.enqueue(Unit, native)
            native.fill(0)
            receive(service, peer)
            assertEquals(setOf(2L), pending(stream).keys)
            assertArrayEquals(expected, pending(stream).getValue(2))
            assertEquals(expected.size, field(stream, "pendingBytes"))
            service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            receive(service, peer)
            assertEquals(setOf(2L), pending(stream).keys)
            service.disconnect(Unit)
            assertTrue(pending(stream).isEmpty())
            assertNull(field(stream, "outgoing"))
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun malformedIngressRetiresPeerQueuesAndReplacementGetsFreshStorage() {
        val failures = mutableListOf<Throwable>()
        RemoteScreenService<Unit, Unit>(RemoteEndpoint.Proxy, { _, _ -> }, failures::add).use { service ->
            service.join(Unit)
            val retired = peer(service)
            val stream = field(retired, "stream") as RemotePacketStream
            val address = field(retired, "address") as RemoteAddress
            service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            service.enqueue(Unit, RemotePacket.encode(RemotePacket.Frame(address, 2, ByteArray(17))))
            receive(service, retired)
            assertEquals(1, pending(stream).size)
            service.enqueue(Unit, byteArrayOf(2))
            service.tick()
            assertEquals(1, failures.size)
            assertTrue(pending(stream).isEmpty())
            assertNull(field(stream, "outgoing"))
            service.join(Unit)
            val replacement = field(peer(service), "stream") as RemotePacketStream
            assertTrue(pending(replacement).isEmpty())
            service.close()
            assertNull(field(replacement, "outgoing"))
        }
    }

    @Test
    fun loadedServerPhasePreservesTheExactDecoderFailure() {
        RemoteScreenService<Unit, Unit>(RemoteEndpoint.Server, { _, _ -> }, {}).use { service ->
            service.join(Unit)
            val current = peer(service)
            service.enqueue(Unit, byteArrayOf(0, 1))
            val failure = assertThrows(IllegalArgumentException::class.java) { receive(service, current) }
            assertEquals("Trailing discovery data.", failure.message)
        }
    }

    private fun peer(service: RemoteScreenService<Unit, Unit>): Any = checkNotNull((field(service, "peers") as Map<*, *>)[Unit])

    private fun receive(
        service: RemoteScreenService<Unit, Unit>,
        peer: Any,
    ) {
        val phase = service.javaClass.getDeclaredMethod("receivePackets", peer.javaClass, Long::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            phase.invoke(service, peer, 0L)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }

    @Suppress("UNCHECKED_CAST") // The declared queue field has this exact type.
    private fun pending(stream: RemotePacketStream): TreeMap<Long, ByteArray> = field(stream, "pending") as TreeMap<Long, ByteArray>

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target
            .javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target)
}
