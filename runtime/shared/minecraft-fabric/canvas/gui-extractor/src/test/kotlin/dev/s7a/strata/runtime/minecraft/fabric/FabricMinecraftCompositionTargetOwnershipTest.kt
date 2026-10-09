@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks real common allocation ownership with borrowed intermediate pairs, arbitrary parity and every partial allocation failure.
 */
internal class FabricMinecraftCompositionTargetOwnershipTest {
    @Test
    fun finalParityAlwaysSelectsIndependentStorageAndTileCloseNeverClosesTheBorrowedIntermediate() {
        var borrowedCloses = 0
        val scratch = AutoCloseable { borrowedCloses += 1 } to AutoCloseable { borrowedCloses += 1 }
        val outputs = ArrayList<AutoCloseable>()
        for (passes in 1..17) {
            var closes = 0
            var acknowledged = false
            val storage = FabricMinecraftNativeStorage { objects ->
                assertEquals(6, objects.size)
                assertFalse(objects.any { it === scratch.first || it === scratch.second })
                FabricNativeCanvasDestruction { acknowledged }
            }
            val targets = FabricMinecraftCompositionTargets.create(
                storage,
                IntSize(64, 64),
                IntSize(64, passes * 3),
                IntSize(1536, 1),
                { AutoCloseable { closes += 1 } },
                { _, _ -> AutoCloseable { closes += 1 } },
                { AutoCloseable { closes += 1 } },
                scratch,
                passes,
            )
            assertSame(scratch, targets.destinations[(passes + 1) % 2])
            val final = targets.destinations[passes % 2]
            assertNotSame(scratch.first, final.first)
            assertFalse(outputs.any { it === final.first })
            outputs.add(final.first)
            storage.close()
            assertEquals(6, closes)
            assertEquals(0, borrowedCloses)
            assertFalse(storage.isDestroyed())
            acknowledged = true
            assertTrue(storage.isDestroyed())
        }
        scratch.second.close()
        scratch.first.close()
        assertEquals(2, borrowedCloses)
    }

    @Test
    fun eachSharedConstructionFailureKeepsEarlierOwnedObjectsUntilFencedPhysicalAcknowledgement() {
        for (passes in listOf(3, 4)) {
            for (failed in 0..5) {
                var borrowedCloses = 0
                val scratch = AutoCloseable { borrowedCloses += 1 } to AutoCloseable { borrowedCloses += 1 }
                val calls = IntArray(failed)
                var allocation = 0
                var acknowledged = false
                val storage = FabricMinecraftNativeStorage { objects ->
                    assertEquals(failed, objects.size)
                    assertFalse(objects.any { it === scratch.first || it === scratch.second })
                    FabricNativeCanvasDestruction { acknowledged }
                }
                val failure = IllegalStateException("shared allocation failed")
                fun allocate(): AutoCloseable {
                    val index = allocation
                    allocation += 1
                    if (index == failed) throw failure
                    return AutoCloseable { calls[index] += 1 }
                }
                assertSame(
                    failure,
                    assertThrows(IllegalStateException::class.java) {
                        FabricMinecraftCompositionTargets.create(storage, IntSize(64, 64), IntSize(64, passes * 3), IntSize(1536, 1), { allocate() }, { _, _ -> allocate() }, { allocate() }, scratch, passes)
                    },
                )
                storage.close()
                assertTrue(calls.all { it == 1 })
                assertEquals(0, borrowedCloses)
                assertFalse(storage.isDestroyed())
                acknowledged = true
                assertTrue(storage.isDestroyed())
            }
        }
    }
}
