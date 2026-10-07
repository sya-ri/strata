package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies exact disjoint fallback pixels, per-command accounting and bounded changed-region reuse.
 */
internal class FabricMinecraftPortableTilesTest {
    @Test
    fun fractionalClipsTintCutoffAndOverlappingAlphaMatchOneFullRasterAtEveryDensity() {
        val viewport = IntSize(600, 450)
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x80ABCDEF.toInt(), 0x40123456, 0))
        for (scale in 1..4) {
            for (orientation in SampledImageOrientation.entries) {
                val commands =
                    listOf(
                        DrawCommand.FillRectangle(IntRect(0, 0, 600, 450), ArgbColor(0x80456789.toInt())),
                        DrawCommand.PushClip(IntRect(7, 11, 592, 441)),
                        DrawCommand.PushFractionalClip(FloatRect(9.25f, 12.625f, 589.75f, 439.125f)),
                        DrawCommand.SampledImage(image, FloatRect(0.125f, 0.25f, 1.875f, 1.75f), FloatRect(11.375f, 13.25f, 588.625f, 440.75f), ArgbColor(0xC0AABBCC.toInt()), 0.2f, orientation),
                        DrawCommand.FillRectangle(IntRect(240, 240, 275, 275), ArgbColor(0x40ABCDEF)),
                        DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(250, 248, 266, 268)),
                        DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(254, 252, 258, 256)),
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                        DrawCommand.FillRectangle(IntRect(256, 256, 257, 257), ArgbColor(-1)),
                    )
                val inputs = inputs(commands, viewport, scale)
                assertTrue(1 < inputs.portable.size && inputs.portable.size <= 64)
                assertArrayEquals(rasterizeHeadless(commands, viewport, scale).copyArgb(), assemble(inputs, viewport, scale), "GUI$scale $orientation")
                assertEquals(1L, inputs.ineligibleSampledImages)
                assertEquals(1L, inputs.tintFallbackImages)
                assertEquals(0L, inputs.alphaCutoffFallbackImages)
            }
        }
    }

    @Test
    fun localChangesMovingRemovalAndBackgroundReplacementInvalidateOnlyAffectedRegions() {
        val viewport = IntSize(1920, 1080)
        val background =
            DrawCommand.FillRectangle(IntRect(0, 0, 1920, 1080), ArgbColor(0xFF234567.toInt()))
        val cell = DrawCommand.FillRectangle(IntRect(20, 20, 30, 30), ArgbColor(-1))
        for (scale in 1..4) {
            val before = inputs(listOf(background, cell), viewport, scale)
            val edited = inputs(listOf(background, cell.copy(color = ArgbColor(0xFF112233.toInt()))), viewport, scale)
            assertEquals(1, matchFabricMinecraftPortableImages(before.portable, edited.portable).count { it < 0 })
            val removed = inputs(listOf(background), viewport, scale)
            assertEquals(1, matchFabricMinecraftPortableImages(before.portable, removed.portable).count { it < 0 })
            val edge =
                before.portable
                    .first()
                    .size.width
            val moved = inputs(listOf(background, cell.copy(bounds = IntRect(edge + 20, 20, edge + 30, 30))), viewport, scale)
            assertEquals(2, matchFabricMinecraftPortableImages(before.portable, moved.portable).count { it < 0 })
            val changedBackground = inputs(listOf(background.copy(color = ArgbColor(-1)), cell), viewport, scale)
            assertEquals(before.portable.size, matchFabricMinecraftPortableImages(before.portable, changedBackground.portable).count { it < 0 })
            assertTrue(before.portable.size <= 64)
        }
        val before = inputs(listOf(background, cell), viewport, 1)
        val clipped = listOf(background, DrawCommand.PushClip(IntRect(18, 18, 32, 32)), cell, DrawCommand.PopClip)
        val afterClip = inputs(clipped, viewport, 1)
        assertEquals(1, matchFabricMinecraftPortableImages(before.portable, afterClip.portable).count { it < 0 })
        assertArrayEquals(rasterizeHeadless(clipped, viewport).copyArgb(), assemble(afterClip, viewport, 1))
    }

    @Test
    fun duplicatedSampleOccurrencesAreCountedOnceEachAndUnavailableLargeSourcesAreTiled() {
        val viewport = IntSize(600, 450)
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 600f, 450f), alphaCutoff = 0f)
        val tinted = sampled.copy(tint = ArgbColor(0x80FFFFFF.toInt()), alphaCutoff = 0.1f)
        val cutoff = sampled.copy(alphaCutoff = 0.2f)
        val inputs = inputs(listOf(tinted, tinted, cutoff), viewport, 1)
        assertEquals(3L, inputs.ineligibleSampledImages)
        assertEquals(2L, inputs.tintFallbackImages)
        assertEquals(1L, inputs.alphaCutoffFallbackImages)
        val direct = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(sampled), viewport, exactSampling = true), 1)
        val capacity = direct.resolve({ false }) { true }
        assertEquals(1L, capacity.capacitySampledImages)
        assertEquals(0L, capacity.ineligibleSampledImages)
        assertTrue(1 < capacity.portable.size)
        assertArrayEquals(rasterizeHeadless(listOf(sampled), viewport).copyArgb(), assemble(capacity, viewport, 1))
        assertEquals(1L, direct.resolve({ false }) { false }.ineligibleSampledImages)
    }

    @Test
    fun smallRunsRemainTightAndPhysicalDimensionsRejectOverflow() {
        val commands = listOf(DrawCommand.FillRectangle(IntRect(10, 20, 12, 22), ArgbColor(-1)))
        val inputs = inputs(commands, IntSize(600, 450), 4)
        assertEquals(1, inputs.portable.size)
        assertEquals(IntSize(2, 2), inputs.portable.single().size)
        assertThrows(ArithmeticException::class.java) {
            tileFabricMinecraftPortable(commands, IntRect(0, 0, Int.MAX_VALUE, 1), 2)
        }
    }

    private fun inputs(
        commands: List<DrawCommand>,
        viewport: IntSize,
        scale: Int,
    ): FabricMinecraftFrameInputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(commands, viewport, scale), scale)

    private fun assemble(
        inputs: FabricMinecraftFrameInputs,
        viewport: IntSize,
        scale: Int,
    ): IntArray {
        val width = viewport.width * scale
        val result = IntArray(width * viewport.height * scale)
        val occupied = BooleanArray(result.size)
        for (image in inputs.portable) {
            val pixels = image.rasterize().copyArgb()
            for (y in 0 until image.physicalSize.height) {
                val start = (image.origin.y * scale + y) * width + image.origin.x * scale
                for (x in 0 until image.physicalSize.width) {
                    check(occupied[start + x].not()) { "Tiles must not overlap." }
                    occupied[start + x] = true
                    result[start + x] = pixels[y * image.physicalSize.width + x]
                }
            }
        }
        return result
    }
}
