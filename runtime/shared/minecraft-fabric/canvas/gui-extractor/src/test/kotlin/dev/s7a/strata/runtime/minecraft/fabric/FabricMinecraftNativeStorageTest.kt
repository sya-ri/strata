@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies bounded ownership, partial allocation, independent close failures, retry, and delayed physical acknowledgement.
 */
internal class FabricMinecraftNativeStorageTest {
    @Test
    fun partialInitializationRetainsItsSuccessfulAllocationUntilPhysicalAcknowledgement() {
        var acknowledged = false
        var closes = 0
        val resource = AutoCloseable { closes += 1 }
        val storage =
            FabricMinecraftNativeStorage { objects ->
                assertEquals(listOf(resource), objects)
                assertEquals(0, closes)
                FabricNativeCanvasDestruction { acknowledged }
            }
        assertSame(resource, storage.allocate { resource })
        val failure = IllegalArgumentException("allocation failed")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { storage.allocate<AutoCloseable> { throw failure } })
        storage.close()
        assertEquals(1, closes)
        assertFalse(storage.isDestroyed())
        acknowledged = true
        assertTrue(storage.isDestroyed())
        storage.close()
        assertEquals(1, closes)
    }

    @Test
    fun failedClosesAttemptEveryIndependentObjectAndRetryOnlyUnclosedObjects() {
        val calls = IntArray(3)
        val primary = IllegalStateException("last allocation close failed")
        val secondary = IllegalArgumentException("first allocation close failed")
        val storage = FabricMinecraftNativeStorage { FabricNativeCanvasDestruction { true } }
        for (index in calls.indices) {
            storage.allocate {
                AutoCloseable {
                    calls[index] += 1
                    if (calls[index] == 1 && index == 2) throw primary
                    if (calls[index] == 1 && index == 0) throw secondary
                }
            }
        }
        assertSame(primary, assertThrows(IllegalStateException::class.java) { storage.close() })
        assertEquals(listOf(secondary), primary.suppressed.toList())
        assertEquals(listOf(1, 1, 1), calls.toList())
        assertThrows(IllegalStateException::class.java) { storage.isDestroyed() }
        assertThrows(IllegalStateException::class.java) { storage.allocate { error("Retiring owners cannot allocate") } }
        storage.close()
        assertEquals(listOf(2, 1, 2), calls.toList())
        assertTrue(storage.isDestroyed())
    }

    @Test
    fun capacityAndFailedDestructionProbeStopAcquisitionBeforeCallingTheAllocator() {
        val storage = FabricMinecraftNativeStorage { error("probe failed") }
        repeat(5) { storage.allocate { AutoCloseable {} } }
        var called = false
        assertThrows(IllegalStateException::class.java) {
            storage.allocate {
                called = true
                AutoCloseable {}
            }
        }
        assertFalse(called)
        assertThrows(IllegalStateException::class.java) { storage.close() }
        assertThrows(IllegalStateException::class.java) {
            storage.allocate {
                called = true
                AutoCloseable {}
            }
        }
        assertFalse(called)
    }
}
