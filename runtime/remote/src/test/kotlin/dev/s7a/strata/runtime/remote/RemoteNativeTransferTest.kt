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
import java.util.UUID

/**
 * Independently verifies the reserved-header layout, single-copy storage, actual-send identity and terminal release.
 * Retained callback arrays remain stable after further flush, cancellation, source mutation and close.
 */
internal class RemoteNativeTransferTest {
    private val address = RemoteAddress(RemoteEndpoint.Proxy, UUID(0, 1))

    @Test
    fun finalArraysAreBuiltWithHeadroomAndPublishedWithoutCopyingAtEveryBoundary() {
        listOf(1, 48, 24534, 24535, 1048576, RemotePacket.limits.messageBytes - 1).forEach { size ->
            val expected = ByteArray(size) { (it * 31 + 7).toByte() }
            val source = expected.copyOf()
            val admitted = mutableListOf<Int>()
            val published = mutableListOf<ByteArray>()
            RemoteFraming(RemotePacket.limits).use { framing ->
                val transfer = framing.nativeTransfer(source, admitted::add)
                val original = frames(transfer).toList()
                assertEquals(admitted.sum(), transfer.queuedBytes)
                assertEquals(original.size.toLong() * 26, transfer.retainedHeadroom)
                assertTrue(original.all { it.take(26).all { value -> value == 0.toByte() } })
                assertTrue(original.all { it.size <= RemoteLimits().frameBytes })
                source.fill(0)
                RemotePacketStream(address, send = published::add).use { stream ->
                    assertEquals(1L, field(stream, "nextOutgoing"))
                    original.indices.forEach { index ->
                        stream.sendNative(transfer)
                        assertSame(original[index], published[index])
                        assertArrayEquals(reference(expected, index), published[index])
                        assertEquals((original.size - index - 1).toLong() * 26, transfer.retainedHeadroom)
                    }
                    assertEquals(original.size + 1L, field(stream, "nextOutgoing"))
                }
                assertTrue(frames(transfer).isEmpty())
                assertNull(field(transfer, "owner"))
                transfer.close()
                assertTrue(published.indices.all { index -> published[index].contentEquals(reference(expected, index)) })
            }
        }
    }

    @Test
    fun privateCancellationReadsTheInnerHeaderAndOnlyFlushConsumesAnOuterSequence() {
        val bytes = ByteArray(24535) { 7 }
        val published = mutableListOf<ByteArray>()
        RemoteFraming(RemotePacket.limits).use { framing ->
            val unsent = framing.nativeTransfer(bytes) { }
            val active = framing.nativeTransfer(bytes) { }
            unsent.close()
            assertNull(field(unsent, "owner"))
            RemotePacketStream(address, send = published::add).use { stream ->
                assertEquals(1L, field(stream, "nextOutgoing"))
                stream.sendNative(active)
                val retainedFirst = published.single().copyOf()
                val cancellation = active.cancellation()
                assertEquals(17, cancellation.queuedBytes)
                assertEquals(26L, cancellation.retainedHeadroom)
                assertEquals(2L, field(stream, "nextOutgoing"))
                active.close()
                assertTrue(frames(active).isEmpty())
                stream.sendNative(cancellation)
                assertEquals(3L, field(stream, "nextOutgoing"))
                assertArrayEquals(retainedFirst, published.first())
                val expected = ByteBuffer.allocate(43).put(1.toByte()).put(1.toByte()).putLong(0).putLong(1).putLong(2).putLong(2).putInt(0).putInt(0).put(0).array()
                assertArrayEquals(expected, published.last())
                assertNull(field(cancellation, "owner"))
                RemoteFraming(RemotePacket.limits).use { receiver ->
                    assertNull(receiver.receive((RemotePacket.decode(published.first()) as RemotePacket.Frame).bytes, 0))
                    assertNull(receiver.receive((RemotePacket.decode(published.last()) as RemotePacket.Frame).bytes, 0))
                    assertNull(field(receiver, "pending"))
                }
            }
        }
    }

    @Test
    fun wrongOwnerAndFailedOrExhaustedDeliveryCannotRetainUnsentStorage() {
        val first = RuntimeExecutionOwner()
        val second = RuntimeExecutionOwner()
        val transfer = first.run { RemoteFraming(RemotePacket.limits).use { it.nativeTransfer(byteArrayOf(1)) { } } }
        second.run {
            assertThrows(IllegalStateException::class.java) { transfer.takeFirst() }
            assertThrows(IllegalStateException::class.java) { transfer.cancellation() }
            assertThrows(IllegalStateException::class.java) { transfer.close() }
        }
        first.run {
            val failure = IllegalStateException("writer")
            RemotePacketStream(address, send = { throw failure }).use { stream ->
                assertSame(failure, assertThrows(IllegalStateException::class.java) { stream.sendNative(transfer) })
                assertTrue(frames(transfer).isEmpty())
                assertNull(field(transfer, "owner"))
            }
            val exhausted = RemoteFraming(RemotePacket.limits).use { it.nativeTransfer(byteArrayOf(1)) { } }
            RemotePacketStream(address, send = { error("No exhausted output") }).use { stream ->
                stream.javaClass.getDeclaredField("nextOutgoing").apply { isAccessible = true }.setLong(stream, Long.MAX_VALUE)
                assertThrows(IllegalStateException::class.java) { stream.sendNative(exhausted) }
                assertTrue(frames(exhausted).isEmpty())
                assertNull(field(exhausted, "owner"))
            }
        }
    }

    @Test
    fun oneFragmentAdmissionAndRejectedSecondFragmentKeepTheOriginalCallbackBoundary() {
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 256, pendingBytes = 1024, collectionEntries = 1, treeNodes = 1)
        RemoteFraming(limits).use { framing ->
            val admitted = mutableListOf<Int>()
            framing.nativeTransfer(ByteArray(48) { 3 }, admitted::add).use { transfer ->
                assertEquals(listOf(64), admitted)
                assertEquals(1, transfer.frameCount)
                assertEquals(64, transfer.queuedBytes)
                assertEquals(26L, transfer.retainedHeadroom)
            }
        }
        listOf(false, true).forEach { native ->
            val admitted = mutableListOf<Int>()
            val failure = RemoteProtocolException(RemoteFailure.ResourceLimit, "One fragment admission")
            RemoteFraming(limits).use { framing ->
                val admit: (Int) -> Unit = { bytes ->
                    admitted.add(bytes)
                    if (limits.collectionEntries < admitted.size) throw failure
                }
                val thrown = assertThrows(RemoteProtocolException::class.java) {
                    if (native) framing.nativeTransfer(ByteArray(49) { 3 }, admit).close() else framing.send(ByteArray(49) { 3 }) { admit(it.size) }
                }
                assertSame(failure, thrown)
                assertEquals(listOf(64, 17), admitted)
            }
        }
    }

    @Test
    fun publicFramingAndEncodingKeepTheirInnerOnlyAndDetachedCopyContracts() {
        val source = ByteArray(24535) { 3 }
        val expected = source.copyOf()
        val frames = mutableListOf<ByteArray>()
        RemoteFraming(RemotePacket.limits).use { it.send(source, frames::add) }
        source.fill(0)
        assertEquals(listOf(24550, 17), frames.map { it.size })
        val native = RemotePacket.encode(RemotePacket.Frame(address, 1, frames.first()))
        assertNotSame(frames.first(), native)
        assertArrayEquals(reference(expected, 0), native)
        frames.first().fill(0)
        assertArrayEquals(reference(expected, 0), native)
    }

    /**
     * Complete literal envelope reference independent of production framing and native encoding.
     */
    private fun reference(
        logical: ByteArray,
        index: Int,
    ): ByteArray {
        val offset = index * 24534
        val count = minOf(24534, logical.size - offset)
        return ByteBuffer.allocate(42 + count)
            .put(1.toByte()).put(1.toByte()).putLong(0).putLong(1).putLong(index + 1L)
            .putLong(1).putInt(logical.size).putInt(offset).put(logical, offset, count).array()
    }

    @Suppress("UNCHECKED_CAST") // The private transfer owns exactly an ArrayDeque<ByteArray>.
    private fun frames(transfer: RemoteNativeTransfer): ArrayDeque<ByteArray> = field(transfer, "frames") as ArrayDeque<ByteArray>

    private fun field(
        target: Any,
        name: String,
    ): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
