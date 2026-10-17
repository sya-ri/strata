@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks the uploaded integer lookup against the independent headless rasterizer at translated texel boundaries.
 */
internal class FabricMinecraftSamplingMapTest {
    @Test
    fun mirroredFractionalSelectionsMatchTheOracle() {
        val image = createDrawImage(IntSize(6, 4), IntArray(24) { 0xFF000000.toInt() or (it * 1237) })
        val bounds = IntRect(179, 69, 186, 77)
        for (scale in 1..4) {
            for (orientation in SampledImageOrientation.entries) {
                val command = DrawCommand.SampledImage(image, FloatRect(0.1f, 0.4f, 5.7f, 3.6f), FloatRect(180.25f, 70.25f, 184.75f, 75.75f), orientation = orientation, alphaCutoff = 0f)
                assertTrue(isDirectFabricSampledImage(command, scale, exactSampling = true))
                assertFalse(isDirectFabricSampledImage(command.copy(alphaCutoff = 0.1f), scale, exactSampling = true))
                assertTrue(isDirectFabricSampledImage(command.copy(alphaCutoff = 1f), scale, exactSampling = true))
                val map = FabricMinecraftSamplingMap(command, bounds, scale)
                val pixels = IntArray(map.physicalSize.width * map.physicalSize.height)
                for (y in 0 until map.physicalSize.height) {
                    for (x in 0 until map.physicalSize.width) {
                        val sx = decode(map.indices.argbAt(x, 0)) - 1
                        val sy = decode(map.indices.argbAt(y, 1)) - 1
                        if (0 <= sx && 0 <= sy) pixels[y * map.physicalSize.width + x] = image.argbAt(sx, sy)
                    }
                }
                assertArrayEquals(rasterizeHeadlessRegion(listOf(command), bounds, scale).copyArgb(), pixels, "GUI$scale $orientation")
                if (orientation != SampledImageOrientation.Normal) assertFalse(isDirectFabricSampledImage(command, scale, fractionalSource = true))
            }
        }
    }

    @Test
    fun opaqueCutoffPreservesEveryMaskAcrossSourceAndDestinationAlpha() {
        val image = createDrawImage(IntSize(256, 256)) { x, y -> (x shl 24) or ((x * 73471 + y * 1337) and 0xFFFFFF) }
        val destination = createDrawImage(image.size) { x, y -> (y shl 24) or ((y * 7919 + x * 1337) and 0xFFFFFF) }
        val bounds = IntRect(0, 0, 256, 256)
        val background = DrawCommand.BlitImage(destination, bounds, bounds)
        for (mask in 0..7) {
            val rgb = (if (mask and 1 == 0) 0 else 0xFF0000) or (if (mask and 2 == 0) 0 else 0xFF00) or (if (mask and 4 == 0) 0 else 0xFF)
            val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 256f, 256f), FloatRect(0f, 0f, 256f, 256f), ArgbColor(0xFF000000.toInt() or rgb), alphaCutoff = 1f)
            val map = FabricMinecraftSamplingMap(command, bounds, 1)
            val effects = decode(map.indices.argbAt(2, 2))
            assertEquals(mask, effects and 7)
            assertEquals(255, effects ushr 3)
            val shaderSource =
                createDrawImage(image.size) { x, y ->
                    val source = image.argbAt(x, y)
                    if (source ushr 24 < effects ushr 3) 0 else source and (0xFF000000.toInt() or rgb)
                }
            val shaderCommand = command.copy(image = shaderSource, tint = ArgbColor(-1), alphaCutoff = 0f)
            val expected = rasterizeHeadlessRegion(listOf(background, command), bounds, 1)
            val actual = rasterizeHeadlessRegion(listOf(background, shaderCommand), bounds, 1)
            assertArrayEquals(expected.copyArgb(), actual.copyArgb(), "mask=$mask")
        }
    }

    @Test
    fun onlyIdentityAndOpaqueCutoffsHaveExactMetadata() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        for (cutoff in listOf(0f, 1f)) {
            val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 1f, 1f), alphaCutoff = cutoff)
            val map = FabricMinecraftSamplingMap(command, IntRect(0, 0, 1, 1), 1)
            assertEquals(IntSize(3, 3), map.indices.size)
            val threshold = decode(map.indices.argbAt(2, 2)) ushr 3
            for (sourceAlpha in 0..255) assertEquals(cutoff <= sourceAlpha.toFloat() / 255f, threshold <= sourceAlpha)
            val input = FabricMinecraftPortableImage(listOf(command), IntSize(1, 1), 1, sampling = map)
            assertEquals(IntSize(3, 4), input.reservationSize)
        }
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 1f, 1f), alphaCutoff = 0f)
        for (cutoff in listOf(0.1f, 0.5f, Math.nextDown(1f))) {
            assertThrows(IllegalArgumentException::class.java) { FabricMinecraftSamplingMap(command.copy(alphaCutoff = cutoff), IntRect(0, 0, 1, 1), 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { FabricMinecraftSamplingMap(command.copy(tint = ArgbColor(0xFF00FF00.toInt())), IntRect(0, 0, 1, 1), 1) }
    }

    @Test
    fun effectChangesInvalidateCurrentGenerationWhileEquivalentMovedMasksReuse() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 4f, 4f), ArgbColor(0xFF00FF00.toInt()), alphaCutoff = 1f)
        val first = FabricMinecraftSamplingMap(command, IntRect(0, 0, 4, 4), 1)
        val moved = command.copy(destination = FloatRect(10f, 10f, 14f, 14f))
        assertTrue(first.equivalent(FabricMinecraftSamplingMap(moved, IntRect(10, 10, 14, 14), 1)))
        assertFalse(first.equivalent(FabricMinecraftSamplingMap(command.copy(tint = ArgbColor(-1)), first.bounds, 1)))
        val white = FabricMinecraftSamplingMap(command.copy(tint = ArgbColor(-1)), first.bounds, 1)
        assertFalse(white.equivalent(FabricMinecraftSamplingMap(command.copy(tint = ArgbColor(-1), alphaCutoff = 0f), first.bounds, 1)))
        assertThrows(IllegalArgumentException::class.java) { FabricMinecraftSamplingMap(command.copy(tint = ArgbColor(0xFF123456.toInt())), first.bounds, 1) }
    }

    @Test
    fun translatedOutputsReuseOnlyWhenEveryEncodedTexelAndCoverageRemainEqual() {
        val image = createDrawImage(IntSize(6, 4), IntArray(24) { 0xFF000000.toInt() or (it * 1237) })
        for (scale in 1..4) {
            val firstCommand = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 6f, 4f), FloatRect(0f, 0f, 12f, 8f), alphaCutoff = 0f)
            val movedCommand = firstCommand.copy(destination = FloatRect(180f, 70f, 192f, 78f))
            val first = FabricMinecraftSamplingMap(firstCommand, IntRect(0, 0, 12, 8), scale)
            val moved = FabricMinecraftSamplingMap(movedCommand, IntRect(180, 70, 192, 78), scale)
            assertTrue(first.equivalent(moved))
            val previous = FabricMinecraftPortableImage(listOf(firstCommand), first.bounds.size, scale, sampling = first)
            val next = FabricMinecraftPortableImage(listOf(movedCommand), moved.bounds.size, scale, IntOffset(180, 70), moved)
            assertTrue(previous.equivalent(next))
            assertArrayEquals(previous.rasterize().copyArgb(), next.rasterize().copyArgb())
            val changed = movedCommand.copy(source = FloatRect(0.5f, 0f, 5.5f, 4f))
            assertFalse(first.equivalent(FabricMinecraftSamplingMap(changed, moved.bounds, scale)))
            val uncovered = movedCommand.copy(destination = FloatRect(181f, 70f, 192f, 78f))
            assertFalse(first.equivalent(FabricMinecraftSamplingMap(uncovered, moved.bounds, scale)))
            val replaced = movedCommand.copy(image = createDrawImage(image.size, image.copyArgb()))
            assertFalse(first.equivalent(FabricMinecraftSamplingMap(replaced, moved.bounds, scale)))
        }
        // These equal-looking translations select different texels because the original Float arithmetic differs.
        val local = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 6f, 4f), FloatRect(1f, 1f, 6f, 6f), alphaCutoff = 0f)
        val global = local.copy(destination = FloatRect(180f, 70f, 185f, 75f))
        val before = FabricMinecraftSamplingMap(local, IntRect(0, 0, 7, 8), 3)
        val after = FabricMinecraftSamplingMap(global, IntRect(179, 69, 186, 77), 3)
        assertFalse(before.equivalent(after))
    }

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
