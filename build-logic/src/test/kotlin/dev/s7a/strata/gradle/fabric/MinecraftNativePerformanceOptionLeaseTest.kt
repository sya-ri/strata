package dev.s7a.strata.gradle.fabric

import dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformanceOptionLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Exercises the actual game-independent fixture option helper and its owner and failure lifetime.
 */
internal class MinecraftNativePerformanceOptionLeaseTest {
    @Test
    fun capturesAllValuesWithoutMutationAndRestoresOnce() {
        var first = false
        var second = 60
        var reads = 0
        var writes = 0
        val lease = MinecraftNativePerformanceOptionLease {}
        lease.capture(
            {
                reads++
                first
            },
            {
                first = it
                writes++
            },
            true,
        )
        lease.capture(
            {
                reads++
                second
            },
            {
                second = it
                writes++
            },
            120,
        )
        assertEquals(2, reads)
        assertEquals(0, writes)
        lease.apply()
        assertTrue(first)
        assertEquals(120, second)
        lease.close()
        assertEquals(false, first)
        assertEquals(60, second)
        assertEquals(4, writes)
        lease.close()
        assertEquals(4, writes)
    }

    @Test
    fun rejectsTheWrongOwnerBeforeReadingWritingOrChangingLifetime() {
        var owner = true
        var reads = 0
        var writes = 0
        var value = 60
        val guard = { check(owner) { "wrong owner" } }
        val lease = MinecraftNativePerformanceOptionLease(guard)
        owner = false
        assertThrows(IllegalStateException::class.java) { MinecraftNativePerformanceOptionLease(guard) }
        assertThrows(IllegalStateException::class.java) {
            lease.capture(
                {
                    reads++
                    value
                },
                {
                    writes++
                    value = it
                },
                120,
            )
        }
        assertEquals(0, reads)
        assertEquals(0, writes)
        owner = true
        lease.capture(
            {
                reads++
                value
            },
            {
                writes++
                value = it
            },
            120,
        )
        owner = false
        assertThrows(IllegalStateException::class.java) { lease.apply() }
        assertThrows(IllegalStateException::class.java) { lease.close() }
        assertEquals(0, writes)
        owner = true
        lease.apply()
        owner = false
        assertThrows(IllegalStateException::class.java) { lease.close() }
        assertEquals(120, value)
        owner = true
        lease.close()
        assertEquals(60, value)
        assertEquals(2, writes)
    }

    @Test
    fun restoresEverySnapshotAfterASetterMutatesAndThrows() {
        val values = mutableListOf(15, 30, 60)
        val writes = mutableListOf<Pair<Int, Int>>()
        val failure = IllegalStateException("apply after mutation")
        val lease = MinecraftNativePerformanceOptionLease {}
        values.indices.forEach { index ->
            lease.capture(
                { values[index] },
                { value ->
                    values[index] = value
                    writes.add(index to value)
                    if (index == 1 && value == 120) throw failure
                },
                120,
            )
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { lease.apply() })
        assertEquals(listOf(120, 120, 60), values)
        lease.close()
        assertEquals(listOf(15, 30, 60), values)
        assertEquals(listOf(0 to 120, 1 to 120, 0 to 15, 1 to 30, 2 to 60), writes)
    }

    @Test
    fun attemptsIndependentRestorationsAndPreservesFailureOrder() {
        val first = IllegalStateException("first restore")
        val second = AssertionError("second restore")
        val restored = mutableListOf<Int>()
        val lease = MinecraftNativePerformanceOptionLease {}
        listOf(first, second, null).forEachIndexed { index, failure ->
            lease.capture(
                { index },
                { value ->
                    if (value != 120) {
                        restored.add(index)
                        failure?.let { throw it }
                    }
                },
                120,
            )
        }
        lease.apply()
        val failure = assertThrows(IllegalStateException::class.java) { lease.close() }
        assertSame(first, failure)
        assertEquals(listOf(second), failure.suppressed.toList())
        assertEquals(listOf(0, 1, 2), restored)
        lease.close()
        assertEquals(listOf(0, 1, 2), restored)
    }

    @Test
    fun restoresAfterCheckedCallbackFailuresWithoutReplacingTheCause() {
        val first = IOException("checked restore")
        val second = Throwable("native restore")
        var restored = 0
        val lease = MinecraftNativePerformanceOptionLease {}
        listOf(first, second, null).forEach { failure ->
            lease.capture(
                { 60 },
                { value ->
                    if (value == 60) {
                        restored++
                        failure?.let { throw it }
                    }
                },
                120,
            )
        }
        lease.apply()
        assertSame(first, assertThrows(IOException::class.java) { lease.close() })
        assertEquals(listOf(second), first.suppressed.toList())
        assertEquals(3, restored)
    }

    @Test
    fun doesNotSelfSuppressARepeatedFailure() {
        val failure = IllegalStateException("same restore")
        var restored = 0
        val lease = MinecraftNativePerformanceOptionLease {}
        repeat(2) {
            lease.capture(
                { 60 },
                { value ->
                    if (value == 60) {
                        restored++
                        throw failure
                    }
                },
                120,
            )
        }
        lease.apply()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { lease.close() })
        assertEquals(2, restored)
        assertTrue(failure.suppressed.isEmpty())
    }

    @Test
    fun aSilentlyRejectedRestoreCannotSkipOtherOptionsOrCertifySuccess() {
        var rejected = 60
        var restored = 30
        val lease = MinecraftNativePerformanceOptionLease {}
        lease.capture({ rejected }, { value -> if (value == 120) rejected = value }, 120)
        lease.capture({ restored }, { restored = it }, 120)
        lease.apply()
        assertThrows(IllegalStateException::class.java) { lease.close() }
        assertEquals(120, rejected)
        assertEquals(30, restored)
        lease.close()
    }

    @Test
    fun closesAnUnappliedOrFailedCaptureWithoutWriting() {
        val failure = IllegalArgumentException("capture")
        var writes = 0
        val lease = MinecraftNativePerformanceOptionLease {}
        lease.capture({ 60 }, { writes++ }, 120)
        assertSame(
            failure,
            assertThrows(IllegalArgumentException::class.java) {
                lease.capture({ throw failure }, { writes++ }, 120)
            },
        )
        lease.close()
        assertEquals(0, writes)
        assertThrows(IllegalStateException::class.java) { lease.apply() }
        assertThrows(IllegalStateException::class.java) { lease.capture({ 60 }, { writes++ }, 120) }
        assertEquals(0, writes)
    }
}
