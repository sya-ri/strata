package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies original-coordinate borrowed rasters, lazy initialization, independent immutable images, and preflight safety.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessBorrowedRasterTest {
    @Test
    fun mixedCommandsMatchImmutableRegionsAtAllDensitiesAndKeepExcessStorage() {
        val image = createDrawImage(IntSize(6, 4), IntArray(24) { index -> if (index % 3 == 0) -1 else 0x8066AA22.toInt() })
        val bounds = IntRect(180, 60, 192, 68)
        for (scale in 1..4) {
            val area = bounds.width * bounds.height * scale * scale
            val scratch = IntArray(area + 13) { 0x12345678 }
            for (orientation in SampledImageOrientation.entries) {
                val commands =
                    listOf(
                        DrawCommand.FillRectangle(IntRect(175, 55, 200, 80), ArgbColor(0x406789AB)),
                        DrawCommand.PushClip(bounds),
                        DrawCommand.PushFractionalClip(FloatRect(180.25f, 60.1f, 191.875f, 67.75f)),
                        DrawCommand.BlitImage(image, IntRect(0, 0, 6, 4), IntRect(178, 58, 188, 66)),
                        DrawCommand.SampledImage(image, FloatRect(0.125f, 0.25f, 5.875f, 3.75f), FloatRect(180f, 60f, 185f, 65f), ArgbColor(0x806655AA.toInt()), 0.1f, orientation),
                        DrawCommand.BlitImagePixels(image, IntRect(1, 0, 5, 4), IntRect(183, 59, 193, 67)),
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                    )
                val immutable = rasterizeHeadlessRegion(commands, bounds, scale)
                val expected = immutable.copyArgb()
                rasterizeHeadlessInto(commands, bounds, scale, scratch)
                assertArrayEquals(expected, scratch.copyOf(area))
                for (index in area until scratch.size) assertEquals(0x12345678, scratch[index])
                val small = IntRect(1, 1, 3, 3)
                val smallArea = 4 * scale * scale
                val previousTail = scratch.copyOfRange(smallArea, scratch.size)
                rasterizeHeadlessInto(emptyList(), small, scale, scratch)
                assertArrayEquals(IntArray(smallArea), scratch.copyOf(smallArea))
                assertArrayEquals(previousTail, scratch.copyOfRange(smallArea, scratch.size))
                assertArrayEquals(expected, immutable.copyArgb())
            }
        }
    }

    @Test
    fun borrowedFillCompositionUsesTheRegionAreaAndPreservesTheTail() {
        val bounds = IntRect(12, 10, 526, 522)
        val image = createDrawImage(IntSize(2, 2), intArrayOf(0, -1, 0x80445566.toInt(), 0x40123456))
        val area = bounds.width * bounds.height
        val scratch = IntArray(area + 29) { -1 }
        val commands =
            listOf(
                DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), bounds),
                DrawCommand.FillRectangle(bounds, ArgbColor(0x408855AA)),
                DrawCommand.FillRectangle(bounds, ArgbColor(0x80337799.toInt())),
            )
        rasterizeHeadlessInto(commands, bounds, 1, scratch)
        assertArrayEquals(rasterizeHeadlessRegion(commands, bounds, 1).copyArgb(), scratch.copyOf(area))
        for (index in area until scratch.size) assertEquals(-1, scratch[index])
    }

    @Test
    fun transparentAndClippedFirstCommandsNeverExposePreviousPixels() {
        val bounds = IntRect(180, 60, 184, 64)
        val image =
            createDrawImage(
                IntSize(4, 4),
                IntArray(16) { index ->
                    when (index % 4) {
                        0 -> 0x00123456
                        1 -> -1
                        2 -> 0x80445566.toInt()
                        else -> 0x40123456
                    }
                },
            )
        val inputs =
            listOf(
                emptyList(),
                listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0x00123456))),
                listOf(DrawCommand.BlitImage(image, IntRect(0, 0, 4, 4), bounds)),
                listOf(DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(180f, 60f, 184f, 64f), ArgbColor(0x806655AA.toInt()), 0.1f)),
                listOf(DrawCommand.PushClip(IntRect(0, 0, 4, 4)), DrawCommand.BlitImage(image, IntRect(0, 0, 4, 4), bounds), DrawCommand.PopClip),
                listOf(DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.25f, 3.75f, 3.75f)), DrawCommand.BlitImagePixels(image, IntRect(0, 0, 4, 4), bounds), DrawCommand.PopClip),
            )
        for (scale in 1..4) {
            val area = 16 * scale * scale
            val scratch = IntArray(area + 17) { -1 }
            for (commands in inputs) {
                scratch.fill(-1)
                rasterizeHeadlessInto(commands, bounds, scale, scratch)
                assertArrayEquals(rasterizeHeadlessRegion(commands, bounds, scale).copyArgb(), scratch.copyOf(area))
                for (index in area until scratch.size) assertEquals(-1, scratch[index])
            }
        }
    }

    @Test
    fun invalidInputDoesNotChangeBorrowedPixels() {
        val pixels = IntArray(16) { 0x12345678 }
        val expected = pixels.copyOf()
        assertThrows(IllegalArgumentException::class.java) { rasterizeHeadlessInto(emptyList(), IntRect(-1, 0, 1, 1), 1, pixels) }
        assertThrows(IllegalArgumentException::class.java) { rasterizeHeadlessInto(emptyList(), IntRect(0, 0, 5, 5), 1, pixels) }
        assertThrows(IllegalArgumentException::class.java) { rasterizeHeadlessInto(listOf(DrawCommand.PopClip), IntRect(0, 0, 4, 4), 1, pixels) }
        assertThrows(ArithmeticException::class.java) { rasterizeHeadlessInto(emptyList(), IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1), 2, pixels) }
        assertArrayEquals(expected, pixels)
    }
}
