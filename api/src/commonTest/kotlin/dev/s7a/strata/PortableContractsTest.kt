package dev.s7a.strata

import dev.s7a.strata.internal.platform.PlatformBigInteger
import dev.s7a.strata.internal.platform.PlatformLock
import dev.s7a.strata.internal.platform.PlatformMath
import dev.s7a.strata.internal.platform.appendScalar
import dev.s7a.strata.internal.platform.scalarAt
import dev.s7a.strata.internal.platform.synchronized
import dev.s7a.strata.resource.parseProfileUuid
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs deterministic low-level portability contracts on JVM and JavaScript.
 */
internal class PortableContractsTest {
    @Test
    fun exactArithmeticRejectsOverflowAndPreservesNegativeRounding() {
        assertFailsWith<ArithmeticException> { PlatformMath.addExact(Long.MAX_VALUE, 1L) }
        assertFailsWith<ArithmeticException> { PlatformMath.subtractExact(Long.MIN_VALUE, 1L) }
        assertFailsWith<ArithmeticException> { PlatformMath.multiplyExact(Long.MIN_VALUE, -1L) }
        assertFailsWith<ArithmeticException> { PlatformMath.multiplyExact(1L shl 40, 1L shl 40) }
        assertFailsWith<ArithmeticException> { PlatformMath.toIntExact(1L shl 40) }
        assertEquals(-3L, PlatformMath.floorDiv(-5L, 2L))
        assertEquals(1L, PlatformMath.floorMod(-5L, 2L))
        assertEquals(Long.MIN_VALUE, PlatformMath.floorDiv(Long.MIN_VALUE, -1L))
        assertEquals(0L, PlatformMath.floorMod(Long.MIN_VALUE, -1L))
    }

    @Test
    fun binaryWeightsRemainExactBeyondDoublePrecision() {
        val unit = PlatformBigInteger.valueOf(1L)
        val enormous = unit.shiftLeft(270)
        val tiny = unit.shiftLeft(250)
        assertEquals(1L shl 20, enormous.divide(tiny).longValueExact())
        assertEquals(0, PlatformBigInteger.ZERO.signum())
        assertEquals(1, enormous.add(unit).signum())
        assertEquals(3L, unit.multiply(PlatformBigInteger.valueOf(3L)).longValueExact())
        assertFailsWith<ArithmeticException> { enormous.longValueExact() }
    }

    @Test
    fun unicodeScalarsPreserveSupplementaryAndIsolatedSurrogates() {
        val text = StringBuilder().appendScalar(0x1F600).appendScalar(0x3042).toString()
        assertEquals(3, text.length)
        assertEquals(0x1F600, text.scalarAt(0))
        assertEquals(0x3042, text.scalarAt(2))
        // The JS compiler may replace an isolated surrogate literal while serializing its intermediate representation.
        val isolated = charArrayOf(0xD800.toChar()).concatToString()
        assertEquals(0xD800, isolated.scalarAt(0))
        assertFailsWith<IllegalArgumentException> { StringBuilder().appendScalar(0xD800) }
    }

    @Test
    fun lockOwnershipSurvivesNestedOperationsAndFailure() {
        val lock = PlatformLock()
        assertFalse(lock.isHeldByCurrentThread())
        assertFailsWith<IllegalArgumentException> {
            synchronized(lock) {
                assertTrue(lock.isHeldByCurrentThread())
                synchronized(lock) { assertTrue(lock.isHeldByCurrentThread()) }
                throw IllegalArgumentException("Expected failure")
            }
        }
        assertFalse(lock.isHeldByCurrentThread())
    }

    @Test
    fun profileIdentityAndCallerStateRoundTrip() {
        val text = "01234567-89ab-cdef-8123-456789abcdef"
        val parsed = parseProfileUuid(text)
        assertEquals(text, parsed.toString())
        assertEquals(parsed, parseProfileUuid(text.uppercase()))
        val state = mutableStateOf(parsed)
        state.value = parseProfileUuid(text)
        assertEquals(parsed, state.value)
        assertFailsWith<IllegalArgumentException> { parseProfileUuid("invalid") }
    }
}
