@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies actual connection queuing, flush budgets, cancellation FIFO, resource limits and native terminal ownership.
 * Public legacy output is an independent protocol path used as a complete byte comparison control.
 */
internal class RemoteNativeConnectionTest {
    private val address = RemoteAddress(RemoteEndpoint.Server, UUID(0, 1))

    @Test
    fun completeNativeWireMatchesPublicReferenceWhileRetainedCallbacksStayStable() {
        listOf(64, 256, RemotePacket.limits.frameBytes).forEach { frameBytes ->
            val limits = RemotePacket.limits.copy(frameBytes = frameBytes, reconstructionMillis = 60000)
            val expected = mutableListOf<ByteArray>()
            val actual = mutableListOf<ByteArray>()
            negotiated(false, limits, expected::add).use { legacy ->
                negotiated(true, limits, actual::add).use { native ->
                    listOf(0, 1, 7, 8, 63, 64, 65).forEach { count ->
                        expected.clear()
                        actual.clear()
                        repeat(count) { index ->
                            legacy.connection.send(RemoteMessage.Resynchronize(index + 1L))
                            native.connection.send(RemoteMessage.Resynchronize(index + 1L))
                        }
                        assertEquals(field(legacy.connection, "queuedBytes"), field(native.connection, "queuedBytes"))
                        assertEquals(field(legacy.connection, "queuedFrames"), field(native.connection, "queuedFrames"))
                        val pending = field(native.connection, "queuedFrames") as Int
                        assertEquals(pending.toLong() * 26, native.connection.retainedNativeHeadroom)
                        val before = field(native.stream, "nextOutgoing")
                        assertEquals(before, field(legacy.stream, "nextOutgoing"))
                        legacy.connection.flush()
                        native.connection.flush()
                        assertEquals(minOf(pending, 8), actual.size)
                        while ((field(native.connection, "queuedFrames") as Int) != 0) {
                            legacy.connection.flush()
                            native.connection.flush()
                        }
                        assertEquals(expected.size, actual.size)
                        actual.indices.forEach { index -> assertArrayEquals(expected[index], actual[index]) }
                        assertEquals(0L, native.connection.retainedNativeHeadroom)
                        val retained = actual.map { it.copyOf() }
                        native.connection.send(action(100, 24535))
                        native.connection.flush()
                        native.connection.discardSession(100)
                        native.connection.flush(1000)
                        retained.indices.forEach { index -> assertArrayEquals(retained[index], actual[index]) }
                        // Advance the legacy control through the identical extra operation/identity history.
                        legacy.connection.send(action(100, 24535))
                        legacy.connection.flush()
                        legacy.connection.discardSession(100)
                        legacy.connection.flush(1000)
                    }
                }
            }
        }
    }

    @Test
    fun unsentDiscardUsesNoOuterIdentityAndStartedCancellationPreservesOtherSessionFifo() {
        val limits = RemotePacket.limits.copy(frameBytes = 256, reconstructionMillis = 60000)
        val packets = mutableListOf<ByteArray>()
        negotiated(true, limits, packets::add).use { fixture ->
            packets.clear()
            val connection = fixture.connection
            val initial = field(fixture.stream, "nextOutgoing") as Long
            connection.send(action(1, 4000))
            val unsent = nativeGroups(connection)
            connection.discardSession(1)
            assertEquals(initial, field(fixture.stream, "nextOutgoing"))
            assertEquals(0L, connection.retainedNativeHeadroom)
            unsent.forEach(::assertReleased)
            connection.send(action(2, 4000))
            connection.flush(1)
            val original = packets.single().copyOf()
            val started = nativeGroups(connection)
            connection.send(RemoteMessage.Resynchronize(3))
            connection.send(RemoteMessage.Close(2, RemoteFailure.Replaced))
            started.forEach(::assertReleased)
            connection.flush(100)
            assertArrayEquals(original, packets.first())
            val frames = packets.map { RemotePacket.decode(it) as RemotePacket.Frame }
            assertEquals((initial until initial + frames.size).toList(), frames.map { it.sequence })
            val messages = mutableListOf<RemoteMessage>()
            RemoteFraming(limits).use { framing ->
                val codec = RemoteMessageCodec(limits)
                frames.forEach { frame -> framing.receive(frame.bytes, 0)?.let { messages.add(codec.decode(it)) } }
                assertNull(field(framing, "pending"))
            }
            assertEquals(listOf(RemoteMessage.Resynchronize(3), RemoteMessage.Close(2, RemoteFailure.Replaced)), messages)
            assertEquals(0, field(connection, "queuedFrames"))
            assertEquals(0, field(connection, "queuedBytes"))
            assertEquals(0L, connection.retainedNativeHeadroom)
        }
    }

    @Test
    fun singleEntryLogicalLimitRejectsTheTwoValueControlBeforeQueueAdmissionOnBothRoutes() {
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 256, pendingBytes = 1024, collectionEntries = 1, treeNodes = 1)
        val failures = mutableListOf<String?>()
        listOf(false, true).forEach { native ->
            val output = mutableListOf<ByteArray>()
            negotiated(native, limits, output::add).use { fixture ->
                output.clear()
                val failure = assertThrows(IllegalArgumentException::class.java) { fixture.connection.send(RemoteMessage.Resynchronize(1)) }
                failures.add(failure.message)
                assertTrue(output.isEmpty())
                assertEquals(0, field(fixture.connection, "queuedFrames"))
                assertEquals(0, field(fixture.connection, "queuedBytes"))
                assertTrue((field(fixture.connection, "pending") as Collection<*>).isEmpty())
                assertNull(field(fixture.connection, "outgoing"))
                assertNull(field(fixture.connection, "nativeStream"))
            }
        }
        assertEquals(failures.first(), failures.last())
    }

    @Test
    fun semanticByteAndEntryAdmissionMatchesLegacyAndFailuresReleaseAllNativeReferences() {
        listOf(
            RemoteLimits(frameBytes = 64, messageBytes = 256, pendingBytes = 256, collectionEntries = 8, treeNodes = 1),
            RemoteLimits(frameBytes = 64, messageBytes = 256, pendingBytes = 1024, collectionEntries = 2, treeNodes = 1),
        ).forEach { limits ->
            val old = mutableListOf<ByteArray>()
            val next = mutableListOf<ByteArray>()
            negotiated(false, limits, old::add).use { legacy ->
                negotiated(true, limits, next::add).use { native ->
                    old.clear()
                    next.clear()
                    val retained = mutableListOf<RemoteNativeTransfer>()
                    var accepted = 0
                    while (true) {
                        val message = RemoteMessage.Resynchronize(accepted + 1L)
                        val reference = runCatching { legacy.connection.send(message) }
                        val candidate = runCatching { native.connection.send(message) }
                        assertEquals(reference.isSuccess, candidate.isSuccess)
                        if (candidate.isFailure) {
                            assertEquals(RemoteFailure.ResourceLimit, (candidate.exceptionOrNull() as RemoteProtocolException).reason)
                            assertEquals(reference.exceptionOrNull()?.message, candidate.exceptionOrNull()?.message)
                            break
                        }
                        retained += nativeGroups(native.connection)
                        assertEquals(field(legacy.connection, "queuedBytes"), field(native.connection, "queuedBytes"))
                        assertEquals(field(legacy.connection, "queuedFrames"), field(native.connection, "queuedFrames"))
                        assertEquals((field(native.connection, "queuedFrames") as Int).toLong() * 26, native.connection.retainedNativeHeadroom)
                        accepted++
                    }
                    retained.forEach(::assertReleased)
                    assertNull(field(native.connection, "outgoing"))
                    assertNull(field(native.connection, "nativeStream"))
                    assertEquals(0L, native.connection.retainedNativeHeadroom)
                    assertTrue(next.isEmpty())
                    assertTrue(old.isEmpty())
                }
            }
        }
    }

    @Test
    fun writerFailureReentryAndCloseClearQueuedEnvelopesAndSendingGuard() {
        val failure = IllegalStateException("writer")
        var fail = false
        negotiated(true, RemotePacket.limits) { if (fail) throw failure }.use { fixture ->
            fixture.connection.send(action(1, 100000))
            val retained = nativeGroups(fixture.connection)
            fail = true
            assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.connection.flush() })
            retained.forEach(::assertReleased)
            assertNull(field(fixture.connection, "outgoing"))
            assertNull(field(fixture.connection, "nativeStream"))
            assertEquals(false, field(fixture.connection, "sending"))
            assertEquals(0L, fixture.connection.retainedNativeHeadroom)
        }
        var reenter: (() -> Unit)? = null
        negotiated(true, RemotePacket.limits) { reenter?.invoke() }.use { fixture ->
            fixture.connection.send(action(1, 100000))
            val retained = nativeGroups(fixture.connection)
            reenter = { fixture.connection.flush() }
            assertThrows(IllegalStateException::class.java) { fixture.connection.flush() }
            retained.forEach(::assertReleased)
            assertEquals(false, field(fixture.connection, "sending"))
            assertNull(fixture.connection.capabilities)
        }
        negotiated(true, RemotePacket.limits) { }.use { fixture ->
            fixture.connection.send(action(1, 100000))
            val retained = nativeGroups(fixture.connection)
            fixture.connection.close()
            retained.forEach(::assertReleased)
            assertNull(field(fixture.connection, "nativeStream"))
            assertEquals(0L, fixture.connection.retainedNativeHeadroom)
        }
    }

    @Test
    fun nativeBindingRejectsAnotherOwnerAndMigratingSerialOwnerKeepsArrayOwnership() {
        val first = RuntimeExecutionOwner()
        val second = RuntimeExecutionOwner()
        val packets = mutableListOf<ByteArray>()
        val fixture = first.run { negotiated(true, RemotePacket.limits, packets::add) }
        try {
            second.run {
                assertThrows(IllegalStateException::class.java) { RemoteConnection.native(emptySet(), stream = fixture.stream) }
                assertThrows(IllegalStateException::class.java) { fixture.connection.send(RemoteMessage.Resynchronize(1)) }
            }
            first.run {
                fixture.connection.send(action(1, 100000))
                assertTrue(0 < fixture.connection.retainedNativeHeadroom)
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit {
                    first.run {
                        fixture.connection.flush(100)
                        assertEquals(0L, fixture.connection.retainedNativeHeadroom)
                    }
                }.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        } finally {
            first.run { fixture.close() }
        }
    }

    @Test
    fun queuedBootstrapKeepsItsOriginalFramingUntilTheSmallNegotiatedReply() {
        val limits = RemotePacket.limits.copy(frameBytes = 64)
        val old = mutableListOf<ByteArray>()
        val next = mutableListOf<ByteArray>()
        RemotePacketStream(address, send = old::add).use { legacyStream ->
            RemotePacketStream(address, send = next::add).use { nativeStream ->
                RemoteConnection(emptySet(), limits, legacyStream::send).use { legacy ->
                    RemoteConnection.native(emptySet(), limits, nativeStream).use { native ->
                        legacy.start()
                        native.start()
                        val bootstrap = nativeGroups(native)
                        val hello = RemoteMessageCodec().encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, emptySet()))
                        RemoteFraming(RemotePacket.limits).use { peer ->
                            peer.send(hello) {
                                assertNull(legacy.receive(it, 0))
                                assertNull(native.receive(it, 0))
                            }
                        }
                        legacy.send(RemoteMessage.Resynchronize(1))
                        native.send(RemoteMessage.Resynchronize(1))
                        assertEquals(field(legacy, "queuedBytes"), field(native, "queuedBytes"))
                        legacy.flush(100)
                        native.flush(100)
                        assertEquals(old.size, next.size)
                        old.indices.forEach { index -> assertArrayEquals(old[index], next[index]) }
                        bootstrap.forEach(::assertReleased)
                        assertEquals(0L, native.retainedNativeHeadroom)
                    }
                }
            }
        }
    }

    @Test
    fun exhaustedInnerOrOuterIdentityAndClosedStreamsClearAllPendingNativeGroups() {
        negotiated(true, RemotePacket.limits) { }.use { fixture ->
            val framing = checkNotNull(field(fixture.connection, "framing"))
            framing.javaClass.getDeclaredField("nextOutgoing").apply { isAccessible = true }.setLong(framing, Long.MAX_VALUE - 1)
            fixture.connection.send(RemoteMessage.Resynchronize(1))
            val last = nativeGroups(fixture.connection)
            assertThrows(IllegalStateException::class.java) { fixture.connection.send(RemoteMessage.Resynchronize(2)) }
            last.forEach(::assertReleased)
            assertNull(field(fixture.connection, "outgoing"))
            assertNull(field(fixture.connection, "nativeStream"))
        }
        listOf(false, true).forEach { closeStream ->
            negotiated(true, RemotePacket.limits) { }.use { fixture ->
                fixture.connection.send(action(1, 100000))
                fixture.connection.send(RemoteMessage.Resynchronize(2))
                val retained = nativeGroups(fixture.connection)
                if (closeStream) {
                    fixture.stream.close()
                } else {
                    fixture.stream.javaClass.getDeclaredField("nextOutgoing").apply { isAccessible = true }.setLong(fixture.stream, Long.MAX_VALUE)
                }
                assertThrows(IllegalStateException::class.java) { fixture.connection.flush() }
                retained.forEach(::assertReleased)
                assertEquals(0, field(fixture.connection, "queuedFrames"))
                assertEquals(0, field(fixture.connection, "queuedBytes"))
                assertEquals(false, field(fixture.connection, "sending"))
                assertNull(field(fixture.connection, "nativeStream"))
            }
        }
    }

    @Test
    fun actualServiceConstructionReplacementAndDisconnectOwnTheNativeConnection() {
        val output = mutableListOf<ByteArray>()
        RemoteScreenService<Unit, Unit>(RemoteEndpoint.Server, { _, bytes -> output.add(bytes) }, { throw it }).use { service ->
            repeat(2) {
                service.join(Unit)
                val peer = checkNotNull((field(service, "peers") as Map<*, *>)[Unit])
                val stream = field(peer, "stream") as RemotePacketStream
                val connection = field(peer, "connection") as RemoteConnection
                assertSame(stream, field(connection, "nativeStream"))
                service.enqueue(Unit, byteArrayOf(0))
                service.tick()
                val hello = output.last()
                val original = hello.copyOf()
                val address = field(peer, "address") as RemoteAddress
                var sequence = 1L
                RemoteFraming(RemotePacket.limits).use { sender ->
                    sender.send(RemoteMessageCodec().encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, RemotePacket.limits, emptySet()))) { frame ->
                        service.enqueue(Unit, RemotePacket.encode(RemotePacket.Frame(address, sequence++, frame)))
                    }
                }
                service.tick()
                connection.send(action(1, 100000))
                val retained = nativeGroups(connection)
                if (it == 0) service.join(Unit) else service.disconnect(Unit)
                retained.forEach(::assertReleased)
                assertNull(field(connection, "nativeStream"))
                assertNull(field(connection, "outgoing"))
                assertNull(field(stream, "outgoing"))
                assertArrayEquals(original, hello)
            }
            assertTrue((field(service, "peers") as Map<*, *>).isEmpty())
        }
    }

    private fun action(
        session: Long,
        size: Int,
    ): RemoteMessage.Action = RemoteMessage.Action(session, 1, 1, BuiltinProjection.PointerPress.type, ProjectionValue.Bytes(ByteArray(size) { (it * 31 + 7).toByte() }))

    private fun negotiated(
        native: Boolean,
        limits: RemoteLimits,
        send: (ByteArray) -> Unit,
    ): Fixture {
        val stream = RemotePacketStream(address, send = send)
        val connection = if (native) RemoteConnection.native(emptySet(), limits, stream) else RemoteConnection(emptySet(), limits, stream::send)
        connection.start()
        connection.flush(100)
        RemoteFraming(RemotePacket.limits).use { framing ->
            framing.send(RemoteMessageCodec().encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, emptySet()))) { assertNull(connection.receive(it, 0)) }
        }
        return Fixture(stream, connection)
    }

    private fun nativeGroups(connection: RemoteConnection): List<RemoteNativeTransfer> =
        (field(connection, "pending") as Collection<*>).map { transfer -> field(checkNotNull(transfer), "frames") as RemoteNativeTransfer }

    private fun assertReleased(transfer: RemoteNativeTransfer) {
        assertEquals(0, transfer.frameCount)
        assertEquals(0L, transfer.retainedHeadroom)
        assertNull(field(transfer, "owner"))
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    /**
     * One independently owned packet stream and connection with deterministic terminal release.
     */
    private class Fixture(
        val stream: RemotePacketStream,
        val connection: RemoteConnection,
    ) : AutoCloseable {
        override fun close() {
            connection.close()
            stream.close()
        }
    }
}
