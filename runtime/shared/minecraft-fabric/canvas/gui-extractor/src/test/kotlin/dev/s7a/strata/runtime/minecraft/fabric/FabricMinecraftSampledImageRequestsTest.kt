package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies bounded detached identity requests without native storage, value equality or repeated source enumeration.
 */
internal class FabricMinecraftSampledImageRequestsTest {
    @Test
    fun equalPixelsRetainDistinctFirstOccurrenceIdentities() {
        val first = createDrawImage(IntSize(1, 1), intArrayOf(0x804466AA.toInt()))
        val equalPixels = createDrawImage(first.size, first.copyArgb())
        val absent = createDrawImage(first.size, first.copyArgb())
        assertEquals(first, equalPixels)
        val source = arrayListOf(first, first, equalPixels, first, equalPixels)
        var enumerations = 0
        val requests =
            FabricMinecraftSampledImageRequests(
                sequence {
                    enumerations += 1
                    yieldAll(source)
                },
            )
        source.clear()

        repeat(64) {
            assertEquals(2, requests.images.size)
            assertSame(first, requests.images[0])
            assertSame(equalPixels, requests.images[1])
            assertTrue(first in requests)
            assertTrue(equalPixels in requests)
            assertFalse(absent in requests)
        }
        assertEquals(1, enumerations)
    }

    @Test
    fun distinctStorageRemainsBoundedByCurrentIdentitiesAcrossOccurrenceCounts() {
        for (occurrences in listOf(1, 64, 4096)) {
            for (distinct in listOf(1, minOf(16, occurrences), occurrences).distinct()) {
                val images = List(distinct) { createDrawImage(IntSize(1, 1), intArrayOf(it)) }
                val requests = FabricMinecraftSampledImageRequests((0 until occurrences).asSequence().map { images[it % distinct] })
                assertEquals(distinct, requests.images.size)
                images.forEachIndexed { index, image -> assertSame(image, requests.images[index]) }
                val empty = FabricMinecraftSampledImageRequests(emptySequence())
                assertTrue(empty.images.isEmpty())
                images.forEach { assertFalse(it in empty) }
            }
        }
    }
}
