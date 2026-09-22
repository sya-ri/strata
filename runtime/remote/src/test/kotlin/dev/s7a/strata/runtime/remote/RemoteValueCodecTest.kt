package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies detached round trips and bounded rejection of malformed wire values.
 */
internal class RemoteValueCodecTest {
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
