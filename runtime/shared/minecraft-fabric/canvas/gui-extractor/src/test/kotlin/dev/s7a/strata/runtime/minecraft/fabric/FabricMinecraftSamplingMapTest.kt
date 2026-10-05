@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Checks the uploaded integer lookup against the independent headless rasterizer at translated texel boundaries.
 */
internal class FabricMinecraftSamplingMapTest {
    @Test
    fun encodedIndicesPreserveIntegerFractionalAndHalfPixelCoverageAtEveryDensity() {
        val image = createDrawImage(IntSize(6, 4), IntArray(24) { 0xFF000000.toInt() or (it * 1237) })
        val bounds = IntRect(179, 69, 186, 77)
        for (scale in 1..4) {
            for (source in listOf(FloatRect(0f, 0f, 6f, 4f), FloatRect(0.25f, 0.125f, 5.75f, 3.875f), FloatRect(0.1f, 0.4f, 5.7f, 3.6f))) {
                for (destination in listOf(FloatRect(180f, 70f, 185f, 75f), FloatRect(179.5f, 69.5f, 185.5f, 76.5f), FloatRect(180.25f, 70.25f, 184.75f, 75.75f))) {
                    val command = DrawCommand.SampledImage(image, source, destination, alphaCutoff = 0f)
                    val map = FabricMinecraftSamplingMap(command, bounds, scale)
                    val expected = rasterizeHeadlessRegion(listOf(command), bounds, scale)
                    val pixels = IntArray(map.physicalSize.width * map.physicalSize.height)
                    for (y in 0 until map.physicalSize.height) {
                        for (x in 0 until map.physicalSize.width) {
                            val sx = decode(map.indices.argbAt(x, 0)) - 1
                            val sy = decode(map.indices.argbAt(y, 1)) - 1
                            if (0 <= sx && 0 <= sy) pixels[y * map.physicalSize.width + x] = image.argbAt(sx, sy)
                        }
                    }
                    assertEquals(map.physicalSize.width, decode(map.indices.argbAt(0, 2)) - 1)
                    assertEquals(map.physicalSize.height, decode(map.indices.argbAt(1, 2)) - 1)
                    assertArrayEquals(expected.copyArgb(), pixels, "GUI$scale source=$source destination=$destination")
                }
            }
        }
    }

    @Test
    fun metadataAndOutputFitOneConservativeReservationAndOversizedAxesAreRejected() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 4f, 8f), alphaCutoff = 0f)
        val bounds = IntRect(0, 0, 4, 8)
        val map = FabricMinecraftSamplingMap(command, bounds, 4)
        val input = FabricMinecraftPortableImage(listOf(command), bounds.size, 4, sampling = map)
        assertEquals(IntSize(32, 35), input.reservationSize)
        assertThrows(IllegalArgumentException::class.java) { FabricMinecraftSamplingMap(command, IntRect(0, 0, 4097, 1), 1) }
    }

    private fun decode(color: Int): Int = ((color ushr 16) and 255) or (((color ushr 8) and 255) shl 8) or ((color and 255) shl 16) or (color and 0xFF000000.toInt())
}
