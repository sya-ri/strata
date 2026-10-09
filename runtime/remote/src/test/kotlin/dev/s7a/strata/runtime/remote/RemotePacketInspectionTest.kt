@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Independent accepted/rejected native header matrix and proof that routing metadata retains no payload storage.
 */
internal class RemotePacketInspectionTest {
    @Test
    fun everyKindAndEndpointByteKeepsTheIndependentWireValidationAndErrorOrder() {
        (0..255).forEach { kind ->
            val discovery = byteArrayOf(kind.toByte())
            when (kind) {
                0 -> {
                    assertEquals(RemotePacket.Discovery, RemotePacket.decode(discovery))
                    assertEquals(RemotePacketRoute.Discovery, RemotePacket.inspect(discovery))
                }

                1 -> {
                    reject(discovery, "Truncated routed fragment.")
                }

                else -> {
                    reject(discovery, "Unknown remote packet kind.")
                }
            }
            val frame = native(43, kind = kind)
            when (kind) {
                0 -> reject(frame, "Trailing discovery data.")
                1 -> accept(frame, RemoteEndpoint.Server)
                else -> reject(frame, "Unknown remote packet kind.")
            }
        }
        (0..255).forEach { endpoint ->
            val frame = native(43, endpoint = endpoint)
            when (endpoint) {
                0 -> accept(frame, RemoteEndpoint.Server)
                1 -> accept(frame, RemoteEndpoint.Proxy)
                else -> reject(frame, "Unknown remote endpoint.")
            }
        }
    }

    @Test
    fun nativeAndHeaderLengthBoundariesAndAllSequenceSignsMatchTheLiteralOuterContract() {
        listOf(0, 1, 2, 25, 26, 42, 43, 24575, 24576, 24577).forEach { size ->
            val bytes = native(size)
            when {
                size == 0 || 24576 < size -> reject(bytes, "Invalid native remote packet length.")
                size < 43 -> reject(bytes, "Truncated routed fragment.")
                else -> accept(bytes, RemoteEndpoint.Server)
            }
        }
        listOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE).forEach { sequence ->
            val bytes = native(43, sequence = sequence)
            if (0 < sequence) {
                accept(bytes, RemoteEndpoint.Server)
                assertEquals(sequence, (RemotePacket.decode(bytes) as RemotePacket.Frame).sequence)
            } else {
                reject(bytes, "Invalid routed fragment sequence.")
            }
        }
        reject(native(24577, kind = 0), "Invalid native remote packet length.")
        reject(native(43, endpoint = 2, sequence = 0), "Unknown remote endpoint.")
        reject(native(42, endpoint = 2, sequence = 0), "Truncated routed fragment.")
    }

    @Test
    fun incarnationBitsAndOpaqueInnerCancellationOrGarbageAreNeverInterpretedByRouting() {
        listOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE).forEach { most ->
            listOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE).forEach { least ->
                val bytes = native(43, most = most, least = least)
                accept(bytes, RemoteEndpoint.Server)
                assertEquals(UUID(most, least), (RemotePacket.inspect(bytes) as RemotePacketRoute.Frame).address.incarnation)
            }
        }
        val cancellation = native(43)
        ByteBuffer
            .wrap(cancellation)
            .position(26)
            .putLong(777)
            .putInt(0)
            .putInt(0)
            .put(0)
        accept(cancellation, RemoteEndpoint.Server)
        listOf(0.toByte(), 127.toByte(), (-128).toByte(), (-1).toByte()).forEach { value ->
            val bytes = native(43)
            bytes.fill(value, 26)
            accept(bytes, RemoteEndpoint.Server)
        }
    }

    @Test
    fun publicDecodeStillDetachesAndRetainedRoutingMetadataCannotAliasACompleteNativeInput() {
        val bytes = native(24576, endpoint = 1, most = Long.MIN_VALUE, least = Long.MAX_VALUE)
        bytes.indices.drop(26).forEach { bytes[it] = (it * 31 + 7).toByte() }
        val expected = bytes.copyOf()
        val packet = RemotePacket.decode(bytes) as RemotePacket.Frame
        val route = RemotePacket.inspect(bytes) as RemotePacketRoute.Frame
        val address = RemoteAddress(RemoteEndpoint.Proxy, UUID(Long.MIN_VALUE, Long.MAX_VALUE))
        bytes.fill(0)
        assertEquals(address, route.address)
        assertEquals(address, packet.address)
        assertArrayEquals(expected.copyOfRange(26, expected.size), packet.bytes)
        packet.bytes.fill(0)
        assertEquals(address, route.address)
        val fields = route.javaClass.declaredFields.filter { Modifier.isStatic(it.modifiers).not() }
        assertTrue(fields.isNotEmpty())
        fields.forEach { member ->
            member.isAccessible = true
            assertTrue(member.get(route) is RemoteAddress)
        }
        assertEquals(
            listOf(RemoteEndpoint::class.java, UUID::class.java),
            address.javaClass.declaredFields
                .filter { Modifier.isStatic(it.modifiers).not() }
                .map { it.type },
        )
        assertNull(bytes.firstOrNull { it != 0.toByte() })
    }

    private fun accept(
        bytes: ByteArray,
        endpoint: RemoteEndpoint,
    ) {
        val packet = RemotePacket.decode(bytes) as RemotePacket.Frame
        val route = RemotePacket.inspect(bytes) as RemotePacketRoute.Frame
        assertEquals(endpoint, route.address.endpoint)
        assertEquals(packet.address, route.address)
        assertArrayEquals(bytes.copyOfRange(26, bytes.size), packet.bytes)
    }

    private fun reject(
        bytes: ByteArray,
        message: String,
    ) {
        assertEquals(message, assertThrows(IllegalArgumentException::class.java) { RemotePacket.decode(bytes) }.message)
        assertEquals(message, assertThrows(IllegalArgumentException::class.java) { RemotePacket.inspect(bytes) }.message)
    }

    private fun native(
        size: Int,
        kind: Int = 1,
        endpoint: Int = 0,
        sequence: Long = 1,
        most: Long = 0,
        least: Long = 1,
    ): ByteArray {
        val bytes = ByteArray(size)
        if (0 < size) bytes[0] = kind.toByte()
        if (1 < size) bytes[1] = endpoint.toByte()
        if (26 <= size) {
            ByteBuffer
                .wrap(bytes)
                .position(2)
                .putLong(most)
                .putLong(least)
                .putLong(sequence)
        }
        return bytes
    }
}
