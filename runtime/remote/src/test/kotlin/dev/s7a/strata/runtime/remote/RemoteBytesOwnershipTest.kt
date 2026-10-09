@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks exact wire compatibility, storage ownership, complete bounded publication, and concurrent readers.
 */
internal class RemoteBytesOwnershipTest {
    @Test
    fun ownershipTransferRetainsOnlyTheExactFreshPayloadWhileOrdinaryConstructionCopies() {
        val input = ByteArray(32) { it.toByte() }
        val field = ProjectionValue.Bytes::class.java.getDeclaredField("content").also { it.isAccessible = true }
        assertNotSame(input, field.get(ProjectionValue.Bytes(input)))
        assertSame(input, field.get(ProjectionValue.Bytes.fromOwned(input)))
        // Reflection observes ownership only; the transferred input is never mutated after publication.
    }

    @Test
    fun completePayloadSizesMatchIndependentBigEndianBytes() {
        val codec = RemoteValueCodec()
        for (size in listOf(0, 32, 262144, 1048576, RemoteLimits().messageBytes - 5)) {
            val input = ByteArray(size) { (it * 31).toByte() }
            val value = ProjectionValue.Bytes(input)
            val expected = reference(value)
            val encoded = codec.encode(value)
            assertArrayEquals(expected, encoded)
            val decoded = codec.decode(encoded)
            assertEquals(value, decoded)
            assertEquals(value.hashCode(), decoded.hashCode())
            input.fill(0)
            encoded.fill(0)
            (decoded as ProjectionValue.Bytes).toByteArray().fill(0)
            assertArrayEquals(expected, codec.encode(decoded))
        }
    }

    @Test
    fun directCopyKeepsGrowthAndFollowingFieldsAtTheExactMessageLimit() {
        val codec = RemoteValueCodec(smallLimits())
        val value = ProjectionValue.Sequence(listOf(ProjectionValue.Bytes(ByteArray(237) { it.toByte() }), ProjectionValue.Integer(Long.MIN_VALUE)))
        val encoded = codec.encode(value)
        assertEquals(256, encoded.size)
        assertArrayEquals(reference(value), encoded)
        assertEquals(value, codec.decode(encoded))
        val overflow = ProjectionValue.Sequence(listOf(ProjectionValue.Bytes(ByteArray(238)), ProjectionValue.Integer(0)))
        assertThrows(IllegalArgumentException::class.java) { codec.encode(overflow) }
        assertThrows(IllegalArgumentException::class.java) { codec.encode(ProjectionValue.Bytes(ByteArray(252))) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(257)) }
        assertArrayEquals(reference(value), codec.encode(value))
    }

    @Test
    fun allTruncatedPrefixesInvalidLengthsAndTrailingBytesFailWithoutPoisoningTheNextCall() {
        val codec = RemoteValueCodec()
        val value = ProjectionValue.Sequence(listOf(ProjectionValue.Bytes(byteArrayOf()), ProjectionValue.Bytes(ByteArray(32) { it.toByte() }), ProjectionValue.Text("following"), ProjectionValue.Flag(true)))
        val wire = reference(value)
        for (length in wire.indices) assertThrows(IllegalArgumentException::class.java) { codec.decode(wire.copyOf(length)) }
        for (length in listOf(-1, Int.MAX_VALUE, 33)) {
            val malformed = ByteArrayOutputStream()
            DataOutputStream(malformed).use {
                it.writeByte(5)
                it.writeInt(length)
                it.write(ByteArray(32))
            }
            assertThrows(IllegalArgumentException::class.java) { codec.decode(malformed.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(wire + byteArrayOf(0)) }
        assertEquals(value, codec.decode(wire))
        assertArrayEquals(wire, codec.encode(value))
        val decoded = codec.decode(wire)
        wire.fill(0)
        assertEquals(value, decoded)
    }

    @Test
    fun aggregateAndDepthFailureKeepTheirFailureClassesAndDoNotRetainPartialPayloads() {
        val value = ProjectionValue.Sequence(listOf(ProjectionValue.Bytes(ByteArray(32)), ProjectionValue.Bytes(ByteArray(32))))
        val wire = reference(value)
        val aggregate = RemoteValueCodec(RemoteLimits(valueEntries = 2))
        assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { aggregate.encode(value) }.reason)
        assertEquals(RemoteFailure.ResourceLimit, assertThrows(RemoteProtocolException::class.java) { aggregate.decode(wire) }.reason)
        val shallow = RemoteValueCodec(RemoteLimits(valueDepth = 1))
        assertThrows(IllegalArgumentException::class.java) { shallow.encode(value) }
        assertThrows(IllegalArgumentException::class.java) { shallow.decode(wire) }
        val admitted = ProjectionValue.Bytes(byteArrayOf(1))
        for (codec in listOf(aggregate, shallow)) assertEquals(admitted, codec.decode(codec.encode(admitted)))
    }

    @Test
    fun sharedImmutableValueAndCodecSupportIndependentConcurrentInvocations() {
        val value = ProjectionValue.Sequence(listOf(ProjectionValue.Bytes.fromOwned(ByteArray(4096) { it.toByte() }), ProjectionValue.Text("日本語"), ProjectionValue.Integer(Long.MAX_VALUE)))
        val expected = reference(value)
        val codec = RemoteValueCodec()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                List(8) {
                    Callable {
                        repeat(16) {
                            val encoded = codec.encode(value)
                            assertArrayEquals(expected, encoded)
                            val decoded = codec.decode(encoded)
                            encoded.fill(0)
                            assertEquals(value, decoded)
                            assertArrayEquals(expected, RemoteValueCodec().encode(decoded))
                        }
                    }
                }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun codecRetainsOnlyItsImmutableLimitsAcrossSuccessAndFailure() {
        val codec = RemoteValueCodec()
        val value = ProjectionValue.Bytes(ByteArray(32))
        codec.encode(value)
        codec.decode(codec.encode(value))
        assertThrows(IllegalArgumentException::class.java) { codec.decode(byteArrayOf(5)) }
        val fields = RemoteValueCodec::class.java.declaredFields.filter { it.isSynthetic.not() }
        assertEquals(1, fields.size)
        assertEquals(RemoteLimits::class.java, fields.single().type)
        assertEquals(value, codec.decode(codec.encode(value)))
    }

    private fun smallLimits(): RemoteLimits = RemoteLimits(frameBytes = 64, messageBytes = 256, collectionEntries = 256, treeNodes = 8)

    private fun reference(value: ProjectionValue): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { write(it, value) }
        return bytes.toByteArray()
    }

    private fun write(
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
                val payload = value.value.encodeToByteArray(throwOnInvalidSequence = true)
                output.writeByte(4)
                output.writeInt(payload.size)
                output.write(payload)
            }

            is ProjectionValue.Bytes -> {
                val payload = value.toByteArray()
                output.writeByte(5)
                output.writeInt(payload.size)
                output.write(payload)
            }

            is ProjectionValue.Sequence -> {
                output.writeByte(6)
                output.writeInt(value.values.size)
                value.values.forEach { write(output, it) }
            }
        }
    }
}
