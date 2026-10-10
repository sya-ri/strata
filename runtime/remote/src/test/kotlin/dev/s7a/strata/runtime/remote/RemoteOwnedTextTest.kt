package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Verifies strict subrange decoding, caller ownership, real private assembly lifetimes and terminal failures.
 * Wire values below are independently written with JDK primitives, including malformed payload boundaries.
 */
internal class RemoteOwnedTextTest {
    @Test
    fun scalarBoundariesAndEveryAsciiByteRemainDetachedAtNonzeroFieldOffsets() {
        val texts = listOf("", (0..127).map(Int::toChar).joinToString(""), "\u0080\u07FF\u0800\uD7FF\uE000\uFFFF", "\uD800\uDC00\uDBFF\uDFFF", "e\u0301\uFFFD\uFEFF日本語🎮")
        val codec = RemoteValueCodec()
        for (text in texts) {
            val expected = ProjectionValue.Sequence(listOf(ProjectionValue.Integer(Long.MIN_VALUE), ProjectionValue.Text(text), ProjectionValue.Bytes(byteArrayOf(0, -1)), ProjectionValue.Sequence(listOf(ProjectionValue.Text("tail"), ProjectionValue.Real(-0.0)))))
            val wire = reference(expected)
            assertTrue(codec.encode(expected).contentEquals(wire))
            for (decode in listOf(codec::decode, codec::decodeOwned)) {
                val input = wire.copyOf()
                val actual = decode(input)
                val hash = actual.hashCode()
                input.fill(0)
                assertEquals(expected, actual)
                assertEquals(hash, actual.hashCode())
                assertTrue(codec.encode(actual).contentEquals(wire))
            }
            assertTrue(wire.size <= 512)
            for (length in 0 until wire.size) {
                val truncated = wire.copyOf(length)
                assertThrows(IllegalArgumentException::class.java) { codec.decode(truncated) }
                assertThrows(IllegalArgumentException::class.java) { codec.decodeOwned(truncated) }
            }
        }
    }

    @Test
    fun malformedUtf8CannotReadAcrossItsDeclaredTextBoundary() {
        val malformed =
            mutableListOf(
                byteArrayOf(-64, -128),
                byteArrayOf(-63, -65),
                byteArrayOf(-32, -128, -128),
                byteArrayOf(-16, -128, -128, -128),
                byteArrayOf(-19, -96, -128),
                byteArrayOf(-12, -112, -128, -128),
                byteArrayOf(-62),
                byteArrayOf(-30, -126),
                byteArrayOf(-16, -97, -114),
                byteArrayOf(-62, 65),
                byteArrayOf(-30, 65, -128),
                byteArrayOf(-16, -128, 65, -128),
            )
        (128..191).forEach { malformed.add(byteArrayOf(it.toByte())) }
        (245..255).forEach { malformed.add(byteArrayOf(it.toByte())) }
        val codec = RemoteValueCodec()
        for (payload in malformed) {
            val wire = rawText(payload)
            for (decode in listOf(codec::decode, codec::decodeOwned)) {
                val failure = assertThrows(IllegalArgumentException::class.java) { decode(wire) }
                assertTrue(failure.cause is CharacterCodingException)
                assertEquals(ProjectionValue.Text("valid"), decode(reference(ProjectionValue.Text("valid"))))
            }
        }
        val crossed =
            ByteBuffer
                .allocate(13)
                .put(6)
                .putInt(2)
                .put(4)
                .putInt(1)
                .put(-62)
                .put(1)
                .put(-128)
                .array()
        assertThrows(IllegalArgumentException::class.java) { codec.decode(crossed) }
        assertThrows(IllegalArgumentException::class.java) { codec.decodeOwned(crossed) }
    }

    @Test
    fun bothRoutesPreserveAdmissionAndFailureCategories() {
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 256, valueDepth = 3, collectionEntries = 32, treeNodes = 8, valueEntries = 16)
        val codec = RemoteValueCodec(limits)
        val exact = reference(ProjectionValue.Text("A".repeat(251)))
        assertEquals(256, exact.size)
        assertEquals(codec.decode(exact), codec.decodeOwned(exact))
        val bad =
            listOf(
                ByteArray(257),
                byteArrayOf(4, -1, -1, -1, -1),
                byteArrayOf(4, 0, 0, 1, 0),
                byteArrayOf(127),
                byteArrayOf(1, 2),
                byteArrayOf(0, 0),
                ByteBuffer
                    .allocate(9)
                    .put(3)
                    .putDouble(Double.NaN)
                    .array(),
                ByteBuffer
                    .allocate(9)
                    .put(3)
                    .putDouble(Double.POSITIVE_INFINITY)
                    .array(),
                ByteBuffer
                    .allocate(9)
                    .put(3)
                    .putDouble(Double.NEGATIVE_INFINITY)
                    .array(),
                reference(ProjectionValue.Sequence(List(33) { ProjectionValue.Absent })),
                reference(ProjectionValue.Sequence(listOf(ProjectionValue.Sequence(listOf(ProjectionValue.Sequence(listOf(ProjectionValue.Absent))))))),
            )
        for (wire in bad) {
            val ordinary = assertThrows(IllegalArgumentException::class.java) { codec.decode(wire) }
            val owned = assertThrows(IllegalArgumentException::class.java) { codec.decodeOwned(wire) }
            assertEquals(ordinary.message, owned.message)
            assertEquals(ordinary.cause?.javaClass, owned.cause?.javaClass)
        }
        val aggregate = reference(ProjectionValue.Sequence(List(16) { ProjectionValue.Absent }))
        assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { codec.decode(aggregate) }.reason)
        assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { codec.decodeOwned(aggregate) }.reason)
        val schema = RemoteMessageCodec()
        val badMessages =
            listOf(
                reference(ProjectionValue.Sequence(listOf(ProjectionValue.Integer(127)))),
                reference(ProjectionValue.Sequence(listOf(ProjectionValue.Integer(5), ProjectionValue.Integer(0), ProjectionValue.Integer(1), ProjectionValue.Integer(1)))),
            )
        badMessages.forEach { wire ->
            assertThrows(IllegalArgumentException::class.java) { schema.decode(wire) }
            assertThrows(IllegalArgumentException::class.java) { schema.decodeOwned(wire) }
        }
    }

    @Test
    fun completedPrivateAssemblyCannotChangeTextOrPixelResultsAfterReturn() {
        val image = ImageSource.Pixels(createDrawImage(IntSize(2, 2), intArrayOf(0x00123456, -1, 0x7F00FF00, -65536)))
        val text = UiText.WithFont(UiText.concat(UiText.Literal(""), UiText.Translated("example.count", listOf(UiTextArgument.IntValue(4), UiTextArgument.Text(UiText.Literal("日本語 🎮"))), "")), ResourceId("example", "font"))
        val value = ProjectionValue.Sequence(listOf(RemoteTextCodec.encode(text), RemoteImageCodec().encode(image), ProjectionValue.Text("A".repeat(200))))
        val message = RemoteMessage.Action(1, 1, 1, TYPE, value)
        val codec = RemoteMessageCodec()
        val expected = codec.encode(message)
        val limits = RemoteLimits(frameBytes = 64)
        negotiated(limits).use { connection ->
            val fragments = fragments(expected, limits, 2)
            assertTrue(1 < fragments.size)
            assertNull(connection.receive(fragments.first(), 0))
            val privateAssembly = checkNotNull(pending(connection))
            var actual: RemoteMessage? = null
            fragments.drop(1).forEach { frame -> connection.receive(frame, 0)?.let { actual = it } }
            val result = checkNotNull(actual) as RemoteMessage.Action
            val hash = result.hashCode()
            assertNull(pending(connection))
            privateAssembly.fill(0)
            fragments.forEach { it.fill(0) }
            assertEquals(message, result)
            assertEquals(hash, result.hashCode())
            assertTrue(expected.contentEquals(codec.encode(result)))
            val values = (result.value as ProjectionValue.Sequence).values
            assertEquals(text, RemoteTextCodec.decode(values[0]))
            assertEquals(image, RemoteImageCodec().decode(values[1]))
        }
        for (route in RemoteDecodeRoute.entries) assertEquals(value, route.value(TYPE, value))
    }

    @Test
    fun failedDecodeClosesTheConnectionAndReleasesItsPartialAssembly() {
        val limits = RemoteLimits(frameBytes = 64)
        negotiated(limits).use { connection ->
            val invalid = RemoteMessageCodec().encode(RemoteMessage.Action(1, 1, 1, TYPE, ProjectionValue.Text("x".repeat(100))))
            invalid[invalid.lastIndex] = -128
            val frames = fragments(invalid, limits, 2)
            assertNull(connection.receive(frames.first(), 0))
            assertTrue(pending(connection) != null)
            assertThrows(IllegalArgumentException::class.java) { frames.drop(1).forEach { connection.receive(it, 0) } }
            assertNull(pending(connection))
            assertNull(connection.capabilities)
            assertThrows(IllegalStateException::class.java) { connection.receive(frames.first(), 0) }
        }
        val valid = RemoteMessage.Action(1, 1, 1, TYPE, ProjectionValue.Text("successor"))
        assertEquals(valid, RemoteDecodeRoute.Connection.decode(valid))
    }

    @Test
    fun immutablePublicReadsAreIndependentAndConnectionsRejectOtherOwners() {
        val expected = ProjectionValue.Sequence(listOf(ProjectionValue.Text("日本語🎮"), ProjectionValue.Text("A".repeat(128))))
        val wire = reference(expected)
        val shared = RemoteValueCodec()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = List(100) { index -> Callable { if (index % 2 == 0) shared.decode(wire) else RemoteValueCodec().decode(wire) } }
            pool.invokeAll(calls).forEach { assertEquals(expected, it.get()) }
            negotiated(RemoteLimits()).use { connection ->
                val frame = fragments(RemoteMessageCodec().encode(RemoteMessage.Acknowledgement(1, 1, 1)), RemoteLimits(), 2).single()
                pool.submit(Callable { assertThrows(IllegalStateException::class.java) { connection.receive(frame, 0) } }).get()
                assertEquals(RemoteMessage.Acknowledgement(1, 1, 1), connection.receive(frame, 0))
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun repeatedReceiveAndFailureCyclesRetainOnlyCurrentPendingState() {
        val codec = RemoteMessageCodec()
        val limits = RemoteLimits(frameBytes = 64)
        val inputs = listOf("small", "A".repeat(4096), "日本語🎮".repeat(128), "Aé日本語🎮".repeat(64))
        negotiated(limits).use { connection ->
            repeat(1000) { index ->
                val expected = RemoteMessage.Action(1, 1, 1, TYPE, ProjectionValue.Text(inputs[index % inputs.size]))
                var actual: RemoteMessage? = null
                fragments(codec.encode(expected), limits, index.toLong() + 2).forEach { frame -> connection.receive(frame, 0)?.let { actual = it } }
                assertEquals(expected, actual)
                assertNull(pending(connection))
            }
        }
        repeat(1000) { index ->
            negotiated(limits).use { connection ->
                val frames = fragments(codec.encode(RemoteMessage.Action(1, 1, 1, TYPE, ProjectionValue.Text(inputs[index % inputs.size] + "A".repeat(100)))), limits, 2)
                connection.receive(frames.first(), 0)
                assertTrue(pending(connection) != null)
                if (index % 2 == 0) {
                    connection.close()
                } else {
                    assertThrows(IllegalArgumentException::class.java) { connection.receive(frames.first(), 0) }
                }
                assertNull(pending(connection))
            }
        }
    }

    // Collection verifies that input arrays are released while both owners remain reachable.
    @Suppress("ExplicitGarbageCollectionCall")
    @Test
    fun codecsAndClosedConnectionDoNotRetainOriginalOrPrivateInputArrays() {
        val codec = RemoteMessageCodec()
        val limits = RemoteLimits(frameBytes = 64)
        val connection = negotiated(limits)
        val references = detachedReferences(codec, connection, limits)
        connection.close()
        var attempts = 0
        while (references.any { it.refersTo(null).not() } && attempts < 20) {
            System.gc()
            attempts += 1
        }
        assertTrue(references.all { it.refersTo(null) })
        Reference.reachabilityFence(codec)
        Reference.reachabilityFence(connection)
    }

    private fun detachedReferences(
        codec: RemoteMessageCodec,
        connection: RemoteConnection,
        limits: RemoteLimits,
    ): List<WeakReference<ByteArray>> {
        val input = codec.encode(RemoteMessage.Action(1, 1, 1, TYPE, ProjectionValue.Text("日本語🎮".repeat(128))))
        codec.decode(input)
        val frames = fragments(input, limits, 2)
        connection.receive(frames.first(), 0)
        val assembly = checkNotNull(pending(connection))
        frames.drop(1).forEach { connection.receive(it, 0) }
        return listOf(WeakReference(input), WeakReference(assembly))
    }

    private fun negotiated(limits: RemoteLimits): RemoteConnection =
        RemoteConnection(setOf(TYPE), limits, {}).also { connection ->
            connection.start()
            connection.flush()
            val hello = RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, limits, setOf(TYPE))
            fragments(RemoteMessageCodec().encode(hello), RemotePacket.limits, 1).forEach { assertNull(connection.receive(it, 0)) }
        }

    private fun pending(connection: RemoteConnection): ByteArray? {
        val framingField = RemoteConnection::class.java.getDeclaredField("framing").also { it.isAccessible = true }
        val framing = framingField.get(connection) as RemoteFraming
        val pendingField = RemoteFraming::class.java.getDeclaredField("pending").also { it.isAccessible = true }
        return pendingField.get(framing) as? ByteArray
    }

    private fun fragments(
        bytes: ByteArray,
        limits: RemoteLimits,
        identity: Long,
    ): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        RemoteFraming(limits).use { sender -> sender.send(bytes, frames::add) }
        frames.forEach { ByteBuffer.wrap(it).putLong(0, identity) }
        return frames
    }

    private fun rawText(payload: ByteArray): ByteArray =
        ByteBuffer
            .allocate(5 + payload.size)
            .put(4)
            .putInt(payload.size)
            .put(payload)
            .array()

    private fun reference(value: ProjectionValue): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { writeReference(it, value) }
        return bytes.toByteArray()
    }

    private fun writeReference(
        output: DataOutputStream,
        value: ProjectionValue,
    ) {
        when (value) {
            ProjectionValue.Absent -> {
                output.writeByte(0)
            }

            is ProjectionValue.Flag -> {
                output.writeByte(1)
                output.writeBoolean(value.value)
            }

            is ProjectionValue.Integer -> {
                output.writeByte(2)
                output.writeLong(value.value)
            }

            is ProjectionValue.Real -> {
                output.writeByte(3)
                output.writeDouble(value.value)
            }

            is ProjectionValue.Text -> {
                val bytes = value.value.toByteArray(Charsets.UTF_8)
                output.writeByte(4)
                output.writeInt(bytes.size)
                output.write(bytes)
            }

            is ProjectionValue.Bytes -> {
                val bytes = value.toByteArray()
                output.writeByte(5)
                output.writeInt(bytes.size)
                output.write(bytes)
            }

            is ProjectionValue.Sequence -> {
                output.writeByte(6)
                output.writeInt(value.values.size)
                value.values.forEach { writeReference(output, it) }
            }
        }
    }

    private companion object {
        val TYPE = ProjectionType(ResourceId("example", "text"))
    }
}
