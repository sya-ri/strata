package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.CharacterCodingException

/**
 * Verifies detached round trips and bounded rejection of malformed wire values.
 */
internal class RemoteValueCodecTest {
    @Test
    fun primitiveCursorKeepsJdkWireBytesAndRejectsEveryTruncatedPrefix() {
        val value = ProjectionValue.Sequence(listOf(ProjectionValue.Absent, ProjectionValue.Flag(true), ProjectionValue.Integer(Long.MIN_VALUE), ProjectionValue.Real(-0.0), ProjectionValue.Text("a日本語"), ProjectionValue.Bytes(byteArrayOf(0, -1))))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(6)
            output.writeInt(6)
            output.writeByte(0)
            output.writeByte(1)
            output.writeBoolean(true)
            output.writeByte(2)
            output.writeLong(Long.MIN_VALUE)
            output.writeByte(3)
            output.writeDouble(-0.0)
            output.writeByte(4)
            val text = "a日本語".encodeToByteArray(throwOnInvalidSequence = true)
            output.writeInt(text.size)
            output.write(text)
            output.writeByte(5)
            output.writeInt(2)
            output.write(byteArrayOf(0, -1))
        }
        val expected = bytes.toByteArray()
        val codec = RemoteValueCodec()
        assertEquals(expected.toList(), codec.encode(value).toList())
        assertEquals(value, codec.decode(expected))
        for (length in 0 until expected.size) assertThrows(IllegalArgumentException::class.java) { codec.decode(expected.copyOf(length)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(expected + byteArrayOf(0)) }
        expected.fill(0)
        assertEquals(value, codec.decode(codec.encode(value)))
    }

    @Test
    fun rejectsAggregateValuesEvenWhenEachCollectionFits() {
        val value = ProjectionValue.Sequence(List(3) { ProjectionValue.Sequence(List(3) { ProjectionValue.Absent }) })
        val bytes = RemoteValueCodec().encode(value)
        val bounded = RemoteValueCodec(RemoteLimits(valueEntries = 10))
        assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { bounded.encode(value) }.reason)
        assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { bounded.decode(bytes) }.reason)
    }

    @Test
    fun workDeadlineUsesMonotonicElapsedTime() {
        var time = 0L
        val budget = RemoteWorkBudget(RemoteLimits(reconstructionMillis = 1)) { time }
        budget.visit()
        time = 1_000_001L
        assertEquals(RemoteFailure.TimedOut, assertThrows(RemoteProtocolException::class.java) { budget.checkTime() }.reason)
    }

    @Test
    fun asciiAndUnicodeTextKeepExactStrictUtf8Bytes() {
        val codec = RemoteValueCodec()
        val texts = listOf("", (0..127).map(Int::toChar).joinToString(""), "namespace:path", "日本語 🎮", "a\u0080\u07FF\u0800\uFFFF")
        for (text in texts) {
            val payload = text.encodeToByteArray(throwOnInvalidSequence = true)
            val header = byteArrayOf(4, 0, 0, (payload.size ushr 8).toByte(), payload.size.toByte())
            val expected = header + payload
            assertEquals(expected.toList(), codec.encode(ProjectionValue.Text(text)).toList())
            assertEquals(ProjectionValue.Text(text), codec.decode(expected))
        }
        for (text in listOf("\uD800", "\uDC00", "a\uD800b")) assertThrows(CharacterCodingException::class.java) { codec.encode(ProjectionValue.Text(text)) }
        for (bytes in listOf(byteArrayOf(-64, -128), byteArrayOf(-19, -96, -128), byteArrayOf(-12, -112, -128, -128))) {
            assertThrows(IllegalArgumentException::class.java) { codec.decode(byteArrayOf(4, 0, 0, 0, bytes.size.toByte()) + bytes) }
        }
    }

    @Test
    fun asciiPayloadGrowthKeepsExactBoundariesAndFollowingFields() {
        val codec = RemoteValueCodec(RemoteLimits(frameBytes = 64, messageBytes = 256, collectionEntries = 256, treeNodes = 8))
        val complete = ProjectionValue.Text("A".repeat(251))
        assertEquals(256, codec.encode(complete).size)
        assertEquals(complete, codec.decode(codec.encode(complete)))
        assertThrows(IllegalArgumentException::class.java) { codec.encode(ProjectionValue.Text("A".repeat(252))) }
        val mixed = ProjectionValue.Sequence(listOf(ProjectionValue.Text("B".repeat(237)), ProjectionValue.Integer(Long.MIN_VALUE)))
        val encoded = codec.encode(mixed)
        assertEquals(256, encoded.size)
        assertEquals(mixed, codec.decode(encoded))
        encoded.fill(0)
        assertEquals(mixed, codec.decode(codec.encode(mixed)))
        val overflow = ProjectionValue.Sequence(listOf(ProjectionValue.Text("B".repeat(238)), ProjectionValue.Integer(Long.MIN_VALUE)))
        assertThrows(IllegalArgumentException::class.java) { codec.encode(overflow) }
    }

    @Test
    fun `round trip owns bytes and preserves unicode and numeric limits`() {
        val bytes = byteArrayOf(1, 2, 3)
        val value =
            ProjectionValue.Sequence(
                listOf(
                    ProjectionValue.Absent,
                    ProjectionValue.Flag(true),
                    ProjectionValue.Integer(Long.MIN_VALUE),
                    ProjectionValue.Integer(Long.MAX_VALUE),
                    ProjectionValue.Real(-0.5),
                    ProjectionValue.Text("日本語 🎮"),
                    ProjectionValue.Bytes(bytes),
                ),
            )
        val codec = RemoteValueCodec()
        val encoded = codec.encode(value)
        bytes.fill(0)
        assertEquals(value, codec.decode(encoded))
    }

    @Test
    fun `rejects malformed tags lengths booleans and utf8`() {
        val codec = RemoteValueCodec()
        listOf(
            byteArrayOf(127),
            byteArrayOf(1, 2),
            byteArrayOf(4, 127, -1, -1, -1),
            byteArrayOf(4, 0, 0, 0, 1, -128),
            byteArrayOf(6, -1, -1, -1, -1),
            byteArrayOf(0, 0),
        ).forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { codec.decode(bytes) } }
    }

    @Test
    fun `bounds nesting and aggregate output`() {
        val codec = RemoteValueCodec(RemoteLimits(frameBytes = 64, messageBytes = 128, valueDepth = 2, collectionEntries = 64, treeNodes = 8))
        val nested = ProjectionValue.Sequence(listOf(ProjectionValue.Sequence(listOf(ProjectionValue.Absent))))
        assertThrows(IllegalArgumentException::class.java) { codec.encode(nested) }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(ProjectionValue.Sequence(List(20) { ProjectionValue.Integer(0) }))
        }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(129)) }
    }
}
