@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the immutable byte boundary and privileged destination copying on JVM, Node, and browser hosts.
 */
class ProjectionBytesOwnershipTest {
    @Test
    fun ordinaryConstructionExtractionAndDestinationCopyKeepIndependentStorage() {
        val original = byteArrayOf(0, -1, 127, -128)
        val expected = original.copyOf()
        val value = ProjectionValue.Bytes(original)
        val hash = value.hashCode()
        original.fill(42)
        value.toByteArray().fill(43)
        val destination = ByteArray(8) { 44 }
        value.copyInto(destination, 2)
        assertContentEquals(byteArrayOf(44, 44, 0, -1, 127, -128, 44, 44), destination)
        destination.fill(45)
        assertContentEquals(expected, value.toByteArray())
        assertEquals(ProjectionValue.Bytes(expected), value)
        assertEquals(hash, value.hashCode())
        assertEquals(expected.size, value.size)
    }

    @Test
    fun transferredFreshPayloadHasTheSameImmutableValueContract() {
        val expected = ByteArray(256) { it.toByte() }
        val value = ProjectionValue.Bytes.fromOwned(expected.copyOf())
        val hash = value.hashCode()
        val destination = ByteArray(expected.size)
        value.copyInto(destination, 0)
        assertContentEquals(expected, destination)
        destination.fill(0)
        value.toByteArray().fill(0)
        assertEquals(ProjectionValue.Bytes(expected), value)
        assertEquals(hash, value.hashCode())
        assertContentEquals(expected, value.toByteArray())
    }

    @Test
    fun destinationBoundsAndEmptyPayloadPreserveTheirContracts() {
        val value = ProjectionValue.Bytes(byteArrayOf(1, 2))
        assertFailsWith<IndexOutOfBoundsException> { value.copyInto(ByteArray(1), 0) }
        assertFailsWith<IndexOutOfBoundsException> { value.copyInto(ByteArray(2), -1) }
        assertFailsWith<IndexOutOfBoundsException> { value.copyInto(ByteArray(2), 1) }
        val empty = ProjectionValue.Bytes.fromOwned(byteArrayOf())
        empty.copyInto(byteArrayOf(), 0)
        assertEquals(0, empty.size)
        assertEquals(ProjectionValue.Bytes(byteArrayOf()), empty)
    }
}
