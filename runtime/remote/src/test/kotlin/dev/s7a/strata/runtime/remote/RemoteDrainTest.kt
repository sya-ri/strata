package dev.s7a.strata.runtime.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks empty observations, bounded progress, sequence gaps, callback failures and terminal queue release.
 */
internal class RemoteDrainTest {
    @Test
    fun consecutiveDeliveryStopsAfterOneEmptyProbeAndRetainsTheBudget() {
        listOf(0, 1, 7, 8, 63, 64, 65).forEach { count ->
            val address = RemoteAddress(RemoteEndpoint.Server)
            RemotePacketStream(address, send = {}).use { stream ->
                val pending = ProbedPackets()
                RemotePacketStream::class.java
                    .getDeclaredField("pending")
                    .apply { isAccessible = true }
                    .set(stream, pending)
                repeat(count) { index -> stream.offer(RemotePacket.Frame(address, index + 1L, ByteArray(17) { index.toByte() }), 0) }
                val delivered = mutableListOf<Int>()
                stream.drain(1) { delivered.add(it[0].toInt()) }
                assertEquals((0 until minOf(count, 64)).toList(), delivered)
                assertEquals(minOf(count + 1, 64), pending.probes)
                assertEquals(maxOf(count - 64, 0), pending.size)
                pending.probes = 0
                stream.drain(2) { delivered.add(it[0].toInt()) }
                assertEquals((0 until count).toList(), delivered)
                assertEquals(if (count == 65) 2 else 1, pending.probes)
            }
        }
    }

    @Test
    fun sequenceArrivingAfterAMissingObservationWaitsForTheNextDrain() {
        val address = RemoteAddress(RemoteEndpoint.Server)
        RemotePacketStream(address, send = {}).use { stream ->
            val pending = ProbedPackets()
            RemotePacketStream::class.java
                .getDeclaredField("pending")
                .apply { isAccessible = true }
                .set(stream, pending)
            stream.offer(RemotePacket.Frame(address, 2, ByteArray(17) { 2 }), 0)
            pending.afterEmpty = { stream.offer(RemotePacket.Frame(address, 1, ByteArray(17) { 1 }), 1) }
            val delivered = mutableListOf<Int>()
            stream.drain(1) { delivered.add(it[0].toInt()) }
            assertEquals(emptyList<Int>(), delivered)
            assertEquals(1, pending.probes)
            stream.drain(2) { delivered.add(it[0].toInt()) }
            assertEquals(listOf(1, 2), delivered)
            assertEquals(4, pending.probes)
        }
    }

    @Test
    fun busyDeliveryStillStopsAtTheMaximumAndKeepsTheNextSequence() {
        val address = RemoteAddress(RemoteEndpoint.Server)
        RemotePacketStream(address, send = {}).use { stream ->
            stream.offer(RemotePacket.Frame(address, 1, ByteArray(17)), 0)
            var delivered = 0
            stream.drain(1) {
                delivered++
                stream.offer(RemotePacket.Frame(address, delivered + 1L, ByteArray(17)), 1)
            }
            assertEquals(64, delivered)
            stream.drain(2, 1) { delivered++ }
            assertEquals(65, delivered)
        }
    }

    @Test
    fun laterGapStillExpiresAfterTheBudgetEndsAndAnEmptyDrainReturns() {
        val address = RemoteAddress(RemoteEndpoint.Server)
        RemotePacketStream(address, RemotePacket.limits.copy(assemblyMillis = 10), {}).use { stream ->
            stream.offer(RemotePacket.Frame(address, 1, ByteArray(17)), 0)
            stream.offer(RemotePacket.Frame(address, 3, ByteArray(17)), 0)
            stream.drain(1, 1) { }
            stream.drain(2) { error("The missing sequence cannot be skipped.") }
            val failure = assertThrows(RemoteProtocolException::class.java) { stream.drain(11) { } }
            assertEquals(RemoteFailure.TimedOut, failure.reason)
        }
    }

    @Test
    fun throwingDeliveryPreservesTheFailureAndDoesNotReplayTheRemovedSequence() {
        val address = RemoteAddress(RemoteEndpoint.Server)
        val failure = IllegalStateException("delivery")
        RemotePacketStream(address, send = {}).use { stream ->
            repeat(2) { index -> stream.offer(RemotePacket.Frame(address, index + 1L, ByteArray(17) { index.toByte() }), 0) }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { stream.drain(1) { throw failure } })
            val received = mutableListOf<Int>()
            stream.drain(2) { received.add(it[0].toInt()) }
            assertEquals(listOf(1), received)
        }
    }

    @Test
    fun outgoingWorkKeepsItsEightFrameBudgetAndExactMessageOrder() {
        listOf(0, 1, 7, 8, 63, 64, 65).forEach { count ->
            val frames = mutableListOf<ByteArray>()
            negotiated(frames::add).use { connection ->
                frames.clear()
                repeat(count) { index -> connection.send(RemoteMessage.Resynchronize(index + 1L)) }
                connection.flush()
                assertEquals(minOf(count, 8), frames.size)
                while (frames.size < count) connection.flush()
                connection.flush()
                RemoteFraming().use { framing ->
                    val codec = RemoteMessageCodec()
                    val delivered = frames.map { codec.decode(checkNotNull(framing.receive(it, 0))) }
                    assertEquals((1..count).map { RemoteMessage.Resynchronize(it.toLong()) }, delivered)
                }
            }
        }
    }

    @Test
    fun writeFailureAndReentryReleaseTheSendingGuardAndAllQueuedWork() {
        val failure = IllegalStateException("writer")
        var fail = false
        val connection = negotiated { if (fail) throw failure }
        connection.send(RemoteMessage.Resynchronize(1))
        fail = true
        assertSame(failure, assertThrows(IllegalStateException::class.java) { connection.flush() })
        assertNull(connection.capabilities)
        assertEquals(
            false,
            RemoteConnection::class.java
                .getDeclaredField("sending")
                .apply { isAccessible = true }
                .get(connection),
        )
        assertThrows(IllegalStateException::class.java) { connection.flush() }
        connection.close()

        var reenter: (() -> Unit)? = null
        negotiated { reenter?.invoke() }.use { guarded ->
            guarded.send(RemoteMessage.Resynchronize(1))
            reenter = { guarded.flush() }
            assertThrows(IllegalStateException::class.java) { guarded.flush() }
            assertNull(guarded.capabilities)
            assertEquals(
                false,
                RemoteConnection::class.java
                    .getDeclaredField("sending")
                    .apply { isAccessible = true }
                    .get(guarded),
            )
        }
    }

    @Test
    fun concurrentInboxArrivalAfterEmptyIsOwnedAndAvailableOnTheNextPoll() {
        val inbox = RemoteFrameInbox()
        val producer = Executors.newSingleThreadExecutor()
        try {
            assertNull(inbox.poll())
            val bytes = byteArrayOf(1, 2, 3)
            assertTrue(producer.submit<Boolean> { inbox.offer(bytes) }.get(5, TimeUnit.SECONDS))
            bytes.fill(9)
            assertEquals(listOf<Byte>(1, 2, 3), checkNotNull(inbox.poll()).toList())
            assertNull(inbox.poll())
            inbox.close()
            assertEquals(false, producer.submit<Boolean> { inbox.offer(byteArrayOf(4)) }.get(5, TimeUnit.SECONDS))
            assertNull(inbox.poll())
        } finally {
            inbox.close()
            producer.shutdownNow()
            assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun negotiated(send: (ByteArray) -> Unit): RemoteConnection {
        val connection = RemoteConnection(emptySet(), send = send)
        connection.start()
        connection.flush()
        RemoteFraming().use { framing ->
            framing.send(RemoteMessageCodec().encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, RemoteLimits(), emptySet()))) {
                assertNull(connection.receive(it, 0))
            }
        }
        return connection
    }

    /**
     * Counts actual next-sequence removals; an optional owner-thread hook models arrival after the empty observation.
     */
    private class ProbedPackets : TreeMap<Long, ByteArray>() {
        var probes = 0
        var afterEmpty: (() -> Unit)? = null

        override fun remove(key: Long): ByteArray? {
            probes++
            val result = super.remove(key)
            if (result == null) {
                val callback = afterEmpty
                afterEmpty = null
                callback?.invoke()
            }
            return result
        }
    }
}
