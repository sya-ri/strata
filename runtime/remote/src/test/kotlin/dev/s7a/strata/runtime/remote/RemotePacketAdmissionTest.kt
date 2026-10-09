@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves private decoder storage transfer, public mutation isolation, owner confinement and terminal release.
 * Reflection observes actual storage identities without introducing a production payload accessor.
 */
@Suppress("TooManyFunctions") // Ownership, rejection and cleanup cases share the same private-storage probes.
internal class RemotePacketAdmissionTest {
    private val address = RemoteAddress(RemoteEndpoint.Server, UUID(0, 1))

    @Test
    fun nativeDecodeTransfersExactlyOneDetachedArrayAtEverySizeBoundary() {
        listOf(17, 64, RemotePacket.limits.frameBytes).forEach { size ->
            val expected = ByteArray(size) { (it * 31).toByte() }
            val native = envelope(expected)
            RemotePacketStream(address, send = {}).use { stream ->
                RemotePacketAdmission.decode(native).use { admission ->
                    val decoded = frame(admission).bytes
                    assertNotSame(native, decoded)
                    assertArrayEquals(expected, decoded)
                    native.fill(0)
                    stream.offer(admission, 0)
                    assertSame(decoded, pending(stream).getValue(1))
                    assertReleased(admission)
                    assertEquals(size, field(stream, "pendingBytes"))
                    stream.drain(1) { received ->
                        assertSame(decoded, received)
                        assertArrayEquals(expected, received)
                    }
                    assertEquals(0, field(stream, "pendingBytes"))
                    assertTrue(pending(stream).isEmpty())
                }
            }
        }
    }

    @Test
    fun publicDecodeAndOfferRetainTheirIndependentCopyContracts() {
        listOf(17, 64, RemotePacket.limits.frameBytes).forEach { size ->
            val expected = ByteArray(size) { (it * 17).toByte() }
            val native = envelope(expected)
            val decoded = RemotePacket.decode(native) as RemotePacket.Frame
            native.fill(0)
            assertArrayEquals(expected, decoded.bytes)
            RemotePacketStream(address, send = {}).use { stream ->
                stream.offer(decoded, 0)
                assertNotSame(decoded.bytes, pending(stream).getValue(1))
                decoded.bytes.fill(0)
                stream.drain(1) { assertArrayEquals(expected, it) }
            }
        }
    }

    @Test
    fun oneShotConsumptionCannotReplayOrRetainAFragment() {
        RemotePacketStream(address, send = {}).use { stream ->
            val admission = RemotePacketAdmission.decode(envelope(ByteArray(17)))
            assertEquals(RemotePacketAdmission.Kind.Frame, admission.kind)
            assertEquals(address, admission.address)
            stream.offer(admission, 0)
            assertReleased(admission)
            assertThrows(IllegalStateException::class.java) { stream.offer(admission, 0) }
            admission.close()
            assertEquals(address, admission.address)
            assertEquals(1, pending(stream).size)
            stream.close()
            assertTrue(pending(stream).isEmpty())
            assertNull(field(stream, "outgoing"))
            assertNull(field(stream, "gapSince"))
            assertEquals(0, field(stream, "pendingBytes"))
        }
    }

    @Test
    fun ignoredDuplicateStaleAndCrossIncarnationHandlesReleaseWithoutReplacingStorage() {
        RemotePacketStream(address, send = {}).use { stream ->
            val expected = ByteArray(17) { 3 }
            RemotePacketAdmission.decode(envelope(expected)).use { stream.offer(it, 0) }
            val retained = pending(stream).getValue(1)
            listOf(envelope(expected), envelope(expected, target = address.copy(incarnation = UUID(0, 2)))).forEach { bytes ->
                RemotePacketAdmission.decode(bytes).use { admission ->
                    stream.offer(admission, 0)
                    assertReleased(admission)
                    assertSame(retained, pending(stream).getValue(1))
                    assertEquals(17, field(stream, "pendingBytes"))
                }
            }
            val conflict = RemotePacketAdmission.decode(envelope(ByteArray(17) { 4 }))
            assertThrows(IllegalArgumentException::class.java) { stream.offer(conflict, 0) }
            assertReleased(conflict)
            assertSame(retained, pending(stream).getValue(1))
            stream.drain(1) { assertSame(retained, it) }
            RemotePacketAdmission.decode(envelope(expected)).use { stale ->
                stream.offer(stale, 2)
                assertReleased(stale)
                assertTrue(pending(stream).isEmpty())
            }
        }
    }

    @Test
    fun byteAndEntryExhaustionReleaseRejectedHandlesAndPreserveExistingQueue() {
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 128, pendingBytes = 128, collectionEntries = 8, treeNodes = 1)
        listOf(limits, limits.copy(collectionEntries = 1)).forEach { bound ->
            RemotePacketStream(address, bound, {}).use { stream ->
                val admitted = minOf(2, bound.collectionEntries)
                repeat(admitted) { index ->
                    RemotePacketAdmission.decode(envelope(ByteArray(64), index + 1L)).use { stream.offer(it, 0) }
                }
                val rejected = RemotePacketAdmission.decode(envelope(ByteArray(64), admitted + 1L))
                val failure = assertThrows(RemoteProtocolException::class.java) { stream.offer(rejected, 0) }
                assertEquals(RemoteFailure.ResourceLimit, failure.reason)
                assertReleased(rejected)
                assertEquals(admitted, pending(stream).size)
                assertEquals(admitted * 64, field(stream, "pendingBytes"))
            }
        }
    }

    @Test
    fun tighteningAndClosedStreamConsumeRejectedStorageWithoutRelaxingBounds() {
        val small = RemotePacket.limits.copy(frameBytes = 64)
        RemotePacketStream(address, send = {}).use { stream ->
            stream.limitTo(small)
            stream.limitTo(RemotePacket.limits)
            val oversize = RemotePacketAdmission.decode(envelope(ByteArray(65)))
            assertThrows(IllegalArgumentException::class.java) { stream.offer(oversize, 0) }
            assertReleased(oversize)
            stream.close()
            val closed = RemotePacketAdmission.decode(envelope(ByteArray(17)))
            assertThrows(IllegalStateException::class.java) { stream.offer(closed, 0) }
            assertReleased(closed)
        }
        RemotePacketStream(address, send = {}).use { stream ->
            RemotePacketAdmission.decode(envelope(ByteArray(65), 2)).use { stream.offer(it, 0) }
            assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { stream.limitTo(small) }.reason)
            stream.close()
            assertTrue(pending(stream).isEmpty())
        }
    }

    @Test
    fun gapsRejectBackwardAndExpiredTimeWithoutPublishingRejectedStorage() {
        listOf(0L, 11L).forEach { now ->
            RemotePacketStream(address, RemotePacket.limits.copy(assemblyMillis = 10), {}).use { stream ->
                RemotePacketAdmission.decode(envelope(ByteArray(17), 2)).use { stream.offer(it, 1) }
                val late = RemotePacketAdmission.decode(envelope(ByteArray(17)))
                assertEquals(RemoteFailure.TimedOut, assertThrows(RemoteProtocolException::class.java) { stream.offer(late, now) }.reason)
                assertReleased(late)
                assertEquals(setOf(2L), pending(stream).keys)
            }
        }
    }

    @Test
    fun orderedDeliveryKeepsBudgetsAndThrowingReceiverDoesNotReplayRemovedStorage() {
        listOf(0, 1, 7, 8, 63, 64, 65).forEach { count ->
            RemotePacketStream(address, send = {}).use { stream ->
                (1..count).reversed().forEach { sequence ->
                    RemotePacketAdmission.decode(envelope(ByteArray(17) { sequence.toByte() }, sequence.toLong())).use { stream.offer(it, 0) }
                }
                val output = mutableListOf<Int>()
                stream.drain(1) { output.add(it[0].toInt()) }
                assertEquals((1..minOf(count, 64)).toList(), output)
                stream.drain(2) { output.add(it[0].toInt()) }
                assertEquals((1..count).toList(), output)
                assertTrue(pending(stream).isEmpty())
                assertEquals(0, field(stream, "pendingBytes"))
            }
        }
        val failure = IllegalStateException("receiver")
        RemotePacketStream(address, send = {}).use { stream ->
            repeat(2) { index ->
                RemotePacketAdmission.decode(envelope(ByteArray(17) { index.toByte() }, index + 1L)).use { stream.offer(it, 0) }
            }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { stream.drain(1) { throw failure } })
            assertEquals(setOf(2L), pending(stream).keys)
            stream.drain(2) { assertEquals(1, it[0].toInt()) }
            assertTrue(pending(stream).isEmpty())
        }
    }

    @Test
    fun onlyTheOriginalLogicalOwnerCanConsumeOrReleaseAnUnselectedHandle() {
        val first = RuntimeExecutionOwner()
        val second = RuntimeExecutionOwner()
        val admission = first.run { RemotePacketAdmission.decode(envelope(ByteArray(17))) }
        val stream = first.run { RemotePacketStream(address, send = {}) }
        val other = second.run { RemotePacketStream(address, send = {}) }
        try {
            second.run {
                assertThrows(IllegalStateException::class.java) { stream.offer(admission, 0) }
                assertThrows(IllegalStateException::class.java) { other.offer(admission, 0) }
                assertThrows(IllegalStateException::class.java) { admission.close() }
            }
            first.run { assertEquals(17, frame(admission).bytes.size) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor
                    .submit {
                        first.run {
                            stream.offer(admission, 0)
                            assertReleased(admission)
                            stream.drain(1) { assertEquals(17, it.size) }
                        }
                    }.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        } finally {
            first.run {
                admission.close()
                stream.close()
            }
            second.run { other.close() }
        }
    }

    @Test
    fun discoveryUnselectedAndMalformedPacketsDoNotPublishFrameStorage() {
        RemotePacketAdmission.decode(byteArrayOf(0)).use { discovery ->
            assertEquals(RemotePacketAdmission.Kind.Discovery, discovery.kind)
            assertNull(discovery.address)
            RemotePacketStream(address, send = {}).use { stream ->
                assertThrows(IllegalStateException::class.java) { stream.offer(discovery, 0) }
                assertTrue(pending(stream).isEmpty())
            }
            discovery.close()
            assertReleased(discovery)
        }
        val unselected = RemotePacketAdmission.decode(envelope(ByteArray(17)))
        unselected.close()
        assertReleased(unselected)
        unselected.close()
        val valid = envelope(ByteArray(17))
        val malformed =
            listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(0, 1), valid.copyOf(20), valid.copyOf(RemoteLimits().frameBytes + 1), valid.copyOf().also { it[1] = -1 }, envelope(ByteArray(17), 0))
        malformed.forEach { bytes ->
            val publicFailure = assertThrows(IllegalArgumentException::class.java) { RemotePacket.decode(bytes) }
            val privateFailure = assertThrows(IllegalArgumentException::class.java) { RemotePacketAdmission.decode(bytes) }
            assertEquals(publicFailure.message, privateFailure.message)
        }
        RemotePacketStream(address, send = {}).use { stream ->
            val exhausted = RemotePacketAdmission.decode(envelope(ByteArray(17), Long.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { stream.offer(exhausted, 0) }
            assertReleased(exhausted)
        }
    }

    /**
     * Independent literal envelope encoder; tests do not round-trip solely through the production encoder.
     */
    private fun envelope(
        payload: ByteArray,
        sequence: Long = 1,
        target: RemoteAddress = address,
    ): ByteArray =
        ByteBuffer
            .allocate(26 + payload.size)
            .put(1.toByte())
            .put(target.endpoint.ordinal.toByte())
            .putLong(target.incarnation.mostSignificantBits)
            .putLong(target.incarnation.leastSignificantBits)
            .putLong(sequence)
            .put(payload)
            .array()

    private fun frame(admission: RemotePacketAdmission): RemotePacket.Frame = field(admission, "packet") as RemotePacket.Frame

    @Suppress("UNCHECKED_CAST") // The declared production field is a TreeMap<Long, ByteArray>.
    private fun pending(stream: RemotePacketStream): TreeMap<Long, ByteArray> = field(stream, "pending") as TreeMap<Long, ByteArray>

    private fun assertReleased(admission: RemotePacketAdmission) {
        assertNull(field(admission, "packet"))
        assertNull(field(admission, "owner"))
    }

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
