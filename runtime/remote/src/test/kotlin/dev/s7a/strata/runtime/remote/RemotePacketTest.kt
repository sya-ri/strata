package dev.s7a.strata.runtime.remote

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies detached routing, strict native bounds, and discovery admission before platform integration.
 */
internal class RemotePacketTest {
    @Test
    fun routesBothHostsWithoutChangingTheirFragments() {
        RemoteEndpoint.entries.forEach { endpoint ->
            val address = RemoteAddress(endpoint)
            val source = ByteArray(RemotePacket.limits.frameBytes) { it.toByte() }
            val encoded = RemotePacket.encode(RemotePacket.Frame(address, 1, source))
            assertEquals(RemoteLimits().frameBytes, encoded.size)
            val decoded = RemotePacket.decode(encoded) as RemotePacket.Frame
            assertEquals(address, decoded.address)
            assertArrayEquals(source, decoded.bytes)
            source.fill(0)
            assertArrayEquals(RemotePacket.encode(decoded), encoded)
        }
    }

    @Test
    fun rejectsMalformedEnvelopesBeforeFragmentAdmission() {
        val valid = RemotePacket.encode(RemotePacket.Frame(RemoteAddress(RemoteEndpoint.Server), 1, ByteArray(17)))
        listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(0, 1), valid.copyOf(20), valid.copyOf(RemoteLimits().frameBytes + 1), valid.copyOf().also { it[1] = -1 }).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { RemotePacket.decode(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemotePacket.encode(RemotePacket.Frame(RemoteAddress(RemoteEndpoint.Proxy), 1, ByteArray(RemotePacket.limits.frameBytes + 1)))
        }
    }

    @Test
    fun admitsDiscoveryWhileKeepingIngressBoundedAndOwned() {
        val source = RemotePacket.encode(RemotePacket.Discovery)
        RemoteFrameInbox().use { inbox ->
            inbox.offer(source)
            source[0] = -1
            assertEquals(RemotePacket.Discovery, RemotePacket.decode(checkNotNull(inbox.poll())))
            assertEquals(false, inbox.offer(ByteArray(RemoteLimits().frameBytes + 1)))
            assertEquals(true, inbox.failed)
            assertEquals(null, inbox.poll())
        }
    }
}
