package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the pure portable-image key and checked logical-to-physical extent contract without a native device.
 */
internal class FabricMinecraftPortableImageTest {
    @Test
    fun scaleParticipatesInTheKeyAndPhysicalExtent() {
        val first = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 2)
        val same = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 2)
        val differentScale = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 1)
        val samePhysicalExtent = FabricMinecraftPortableImage(emptyList(), IntSize(6, 4), 1)

        assertEquals(IntSize(6, 4), first.physicalSize)
        assertTrue(first.equivalent(same))
        assertFalse(first.equivalent(differentScale))
        assertFalse(first.equivalent(samePhysicalExtent))
    }

    @Test
    fun invalidAndOverflowingPhysicalExtentsFailBeforeNativeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(0, 1), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(1, 1), 0)
        }
        assertThrows(ArithmeticException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(Int.MAX_VALUE, 1), 2)
        }
    }
}
