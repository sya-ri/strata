package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * Verifies deterministic previous-generation indices without native storage or an additional image cache.
 */
internal class FabricMinecraftPortableReuseTest {
    @Test
    fun insertionsRemovalsAndReplacementsPreserveUnchangedPrefixAndSuffix() {
        val first = image(1)
        val middle = image(2)
        val last = image(3)
        val inserted = image(4)
        assertArrayEquals(intArrayOf(-1, 0, 1, 2), matchFabricMinecraftPortableImages(listOf(first, middle, last), listOf(inserted, first, middle, last)))
        assertArrayEquals(intArrayOf(0, -1, 1, 2), matchFabricMinecraftPortableImages(listOf(first, middle, last), listOf(first, inserted, middle, last)))
        assertArrayEquals(intArrayOf(1, 2), matchFabricMinecraftPortableImages(listOf(first, middle, last), listOf(middle, last)))
        assertArrayEquals(intArrayOf(0, 2), matchFabricMinecraftPortableImages(listOf(first, middle, last), listOf(first, last)))
        assertArrayEquals(intArrayOf(0, -1, 2), matchFabricMinecraftPortableImages(listOf(first, middle, last), listOf(first, inserted, last)))
    }

    @Test
    fun duplicatesEmptyListsAndDifferentPhysicalKeysRemainSafe() {
        val first = image(1)
        assertArrayEquals(intArrayOf(0, 1, -1), matchFabricMinecraftPortableImages(listOf(first, first), listOf(first, first, first)))
        assertArrayEquals(intArrayOf(-1), matchFabricMinecraftPortableImages(emptyList(), listOf(first)))
        assertArrayEquals(intArrayOf(), matchFabricMinecraftPortableImages(listOf(first), emptyList()))
        assertArrayEquals(intArrayOf(-1), matchFabricMinecraftPortableImages(listOf(first), listOf(image(1, 2))))
    }

    private fun image(
        color: Int,
        scale: Int = 1,
    ): FabricMinecraftPortableImage = FabricMinecraftPortableImage(listOf(DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(color))), IntSize(2, 2), scale)
}
