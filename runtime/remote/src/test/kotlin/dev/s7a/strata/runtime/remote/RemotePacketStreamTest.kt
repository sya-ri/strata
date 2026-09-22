package dev.s7a.strata.runtime.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Exercises asynchronous reordering, per-tick budgets, duplicate admission, and terminal queue bounds.
 */
internal class RemotePacketStreamTest {
    @Test
    fun restoresOrderWithoutReplayingDuplicatesOrCrossingIncarnations() {
        val address = RemoteAddress(RemoteEndpoint.Proxy)
        val outgoing = mutableListOf<ByteArray>()
        RemotePacketStream(address, send = outgoing::add).use { sender ->
            repeat(4) { value -> sender.send(ByteArray(17) { value.toByte() }) }
        }
        val frames = outgoing.map { RemotePacket.decode(it) as RemotePacket.Frame }
        val received = mutableListOf<Int>()
        RemotePacketStream(address, send = {}).use { receiver ->
            frames.reversed().forEach { receiver.offer(it, 0) }
            receiver.offer(frames.first(), 0)
            receiver.offer(RemotePacket.Frame(RemoteAddress(RemoteEndpoint.Proxy), 1, frames.first().bytes), 0)
            receiver.drain(1, 2) { received.add(it[0].toInt()) }
            assertEquals(listOf(0, 1), received)
            receiver.drain(2, 2) { received.add(it[0].toInt()) }
            receiver.offer(frames.first(), 3)
            receiver.drain(4) { received.add(it[0].toInt()) }
            assertEquals(listOf(0, 1, 2, 3), received)
        }
    }

    @Test
    fun rejectsConflictingDuplicatesAndExcessPendingWork() {
        val address = RemoteAddress(RemoteEndpoint.Server)
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 64, pendingBytes = 128, collectionEntries = 2, treeNodes = 1)
        RemotePacketStream(address, limits, {}).use { stream ->
            val frame = RemotePacket.Frame(address, 2, ByteArray(17))
            stream.offer(frame, 0)
            assertThrows(IllegalArgumentException::class.java) { stream.offer(RemotePacket.Frame(address, 2, ByteArray(17) { 1 }), 0) }
            stream.offer(RemotePacket.Frame(address, 3, frame.bytes), 0)
            val failure = assertThrows(RemoteProtocolException::class.java) { stream.offer(RemotePacket.Frame(address, 4, frame.bytes), 0) }
            assertEquals(RemoteFailure.ResourceLimit, failure.reason)
        }
    }

    @Test
    fun missingSequencesExpireAndCloseDiscardsBufferedFrames() {
        val address = RemoteAddress(RemoteEndpoint.Server)
        val limits = RemotePacket.limits.copy(assemblyMillis = 10)
        val stream = RemotePacketStream(address, limits, {})
        stream.offer(RemotePacket.Frame(address, 2, ByteArray(17)), 1)
        val failure = assertThrows(RemoteProtocolException::class.java) { stream.drain(11) { error("A gap cannot deliver data.") } }
        assertEquals(RemoteFailure.TimedOut, failure.reason)
        stream.close()
        assertThrows(IllegalStateException::class.java) { stream.drain(12) { error("Closed stream retained data.") } }
    }

    @Test
    fun negotiatedBoundsRejectQueuedOversizeAndCannotBeRelaxed() {
        val address = RemoteAddress(RemoteEndpoint.Proxy)
        val small = RemotePacket.limits.copy(frameBytes = 64)
        RemotePacketStream(address, send = {}).use { stream ->
            stream.offer(RemotePacket.Frame(address, 2, ByteArray(65)), 0)
            val failure = assertThrows(RemoteProtocolException::class.java) { stream.limitTo(small) }
            assertEquals(RemoteFailure.ResourceLimit, failure.reason)
        }
        RemotePacketStream(address, send = {}).use { stream ->
            stream.limitTo(small)
            stream.limitTo(RemotePacket.limits)
            assertThrows(IllegalArgumentException::class.java) { stream.send(ByteArray(RemoteLimits().frameBytes)) }
            assertThrows(IllegalArgumentException::class.java) { stream.offer(RemotePacket.Frame(address, 1, ByteArray(65)), 0) }
            stream.send(ByteArray(64))
        }
    }
}
