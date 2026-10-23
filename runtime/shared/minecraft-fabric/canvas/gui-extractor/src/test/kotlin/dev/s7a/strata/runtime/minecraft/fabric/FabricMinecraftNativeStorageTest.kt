package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
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
    fun completedRetirementRunsOnlyAfterEveryObjectCloseAndNeverFromTheProbe() {
        val closes = IntArray(3)
        var completionCalls = 0
        var physicallyDestroyed = false
        val storage =
            FabricMinecraftNativeStorage {
                object : FabricNativeCanvasDestruction {
                    override fun completeRetirement() {
                        assertEquals(listOf(1, 1, 1), closes.toList())
                        completionCalls += 1
                        physicallyDestroyed = true
                    }

                    override fun isDestroyed(): Boolean = physicallyDestroyed
                }
            }
        repeat(closes.size) { index -> storage.allocate { AutoCloseable { closes[index] += 1 } } }
        assertThrows(IllegalStateException::class.java) { storage.isDestroyed() }
        assertEquals(0, completionCalls)
        storage.close()
        assertTrue(storage.isDestroyed())
        assertTrue(storage.isDestroyed())
        storage.close()
        assertEquals(1, completionCalls)
        assertEquals(listOf(1, 1, 1), closes.toList())
    }

    @Test
    fun failedClosePreventsCompletedRetirementUntilAllCloseRequestsSucceed() {
        val closes = IntArray(2)
        var completionCalls = 0
        val failure = IllegalStateException("close request failed")
        val storage =
            FabricMinecraftNativeStorage {
                object : FabricNativeCanvasDestruction {
                    override fun completeRetirement() {
                        assertEquals(listOf(1, 2), closes.toList())
                        completionCalls += 1
                    }

                    override fun isDestroyed(): Boolean = completionCalls == 1
                }
            }
        repeat(closes.size) { index ->
            storage.allocate {
                AutoCloseable {
                    closes[index] += 1
                    if (index == 1 && closes[index] == 1) throw failure
                }
            }
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { storage.close() })
        assertEquals(0, completionCalls)
        assertThrows(IllegalStateException::class.java) { storage.isDestroyed() }
        storage.close()
        assertTrue(storage.isDestroyed())
        assertEquals(1, completionCalls)
        assertEquals(listOf(1, 2), closes.toList())
    }

    @Test
    fun failedPhysicalRetirementRetainsStorageAndRetryDoesNotRepeatAcceptedCloses() {
        val closes = IntArray(2)
        val physicalCalls = IntArray(2)
        val physicallyDestroyed = BooleanArray(2)
        val failure = IllegalStateException("physical retirement failed")
        var completionCalls = 0
        val storage =
            FabricMinecraftNativeStorage {
                object : FabricNativeCanvasDestruction {
                    override fun completeRetirement() {
                        completionCalls += 1
                        for (index in physicallyDestroyed.indices) {
                            if (physicallyDestroyed[index]) continue
                            physicalCalls[index] += 1
                            if (index == 1 && physicalCalls[index] == 1) throw failure
                            physicallyDestroyed[index] = true
                        }
                    }

                    override fun isDestroyed(): Boolean = physicallyDestroyed.all { it }
                }
            }
        repeat(closes.size) { index -> storage.allocate { AutoCloseable { closes[index] += 1 } } }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { storage.close() })
        assertEquals(listOf(1, 1), closes.toList())
        assertEquals(listOf(true, false), physicallyDestroyed.toList())
        assertThrows(IllegalStateException::class.java) { storage.isDestroyed() }
        storage.close()
        assertTrue(storage.isDestroyed())
        assertEquals(listOf(1, 1), closes.toList())
        assertEquals(listOf(1, 2), physicalCalls.toList())
        assertEquals(2, completionCalls)
    }

    @Test
    fun sharedCompositionConstructionRetainsEveryPartialTextureAndViewOnFailure() {
        for (failed in 0..7) {
            val calls = IntArray(failed)
            var allocation = 0
            var acknowledged = false
            val storage =
                FabricMinecraftNativeStorage { objects ->
                    assertEquals(failed, objects.size)
                    assertTrue(calls.all { it == 0 })
                    FabricNativeCanvasDestruction { acknowledged }
                }
            val failure = IllegalStateException("composition allocation $failed failed")

            fun allocate(): AutoCloseable {
                val index = allocation
                allocation += 1
                if (index == failed) throw failure
                return AutoCloseable { calls[index] += 1 }
            }
            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) {
                    FabricMinecraftCompositionTargets.create(
                        storage,
                        IntSize(64, 64),
                        IntSize(128, 2),
                        IntSize(256, 3),
                        { allocate() },
                        { _, _ -> allocate() },
                        { allocate() },
                    )
                },
            )
            assertEquals(failed + 1, allocation)
            storage.close()
            assertTrue(calls.all { it == 1 })
            assertFalse(storage.isDestroyed())
            acknowledged = true
            assertTrue(storage.isDestroyed())
        }
    }

    @Test
    fun failedAllocationPreservesEarlierOwnershipUntilPhysicalAcknowledgement() {
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
        repeat(9) { storage.allocate { AutoCloseable {} } }
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
