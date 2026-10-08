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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves redundant-pass removal with original commands and an independent wire/pixel oracle, including active-order axis invalidation.
 */
internal class FabricMinecraftCompositionPassesTest {
    private val size = IntSize(64, 64)
    private val bounds = IntRect(0, 0, 64, 64)
    private val source = createDrawImage(IntSize(128, 128)) { x, y -> ((x * 19 + y * 17 and 255) shl 24) or (x * 1337 + y * 7919 and 0xFFFFFF) }

    @Test
    fun zeroAlphaAndStrictCutoffProofsKeepEveryOriginalPixelAndPassBoundary() {
        val reference = FabricMinecraftCompositionMapTest()
        for (alpha in listOf(0, 1, 128, 254, 255)) {
            val maximum = alpha.toFloat() / 255f
            val cutoffs = listOf(0f, Math.nextDown(maximum).coerceAtLeast(0f), maximum, Math.nextUp(maximum).coerceAtMost(1f), 1f).distinct()
            for (cutoff in cutoffs) {
                for (scale in 1..4) {
                    val sample = DrawCommand.SampledImage(source, FloatRect(0.125f, 0.375f, 127.875f, 127.75f), FloatRect(0.25f, 0.125f, 63.875f, 63.625f), ArgbColor((alpha shl 24) or 0x7FC1E3), alphaCutoff = cutoff, orientation = SampledImageOrientation.FlipHorizontal)
                    val commands = listOf(DrawCommand.BlitImagePixels(source, IntRect(0, 0, 128, 128), bounds), DrawCommand.FillRectangle(bounds, ArgbColor(0x00ABCDEF)), sample, DrawCommand.FillRectangle(IntRect(7, 11, 43, 51), ArgbColor(0x01102030)))
                    val map = checkNotNull(FabricMinecraftCompositionMap.create(commands, size, scale, IntOffset.Zero, FabricMinecraftSamplingBudget()))
                    val redundant = alpha == 0 || maximum < cutoff
                    assertEquals(if (redundant) 2 else 3, map.sources.size)
                    assertArrayEquals(rasterizeHeadlessRegion(commands, bounds, scale).copyArgb(), reference.compose(map))
                }
            }
        }
    }

    @Test
    fun swappedActivePassesWithEqualCountsCannotCopyDifferentOldAxes() {
        val firstBounds = IntRect(0, 0, 31, 64)
        val secondBounds = IntRect(32, 0, 64, 64)
        val sample = DrawCommand.SampledImage(source, FloatRect(0f, 0f, 128f, 128f), FloatRect(0f, 0f, 64f, 64f), ArgbColor(0x80BFD7EF.toInt()), alphaCutoff = 0.1f)
        val before = listOf(DrawCommand.FillRectangle(firstBounds, ArgbColor(0x00ABCDEF)), DrawCommand.FillRectangle(secondBounds, ArgbColor(0x80112233.toInt())), sample)
        val after = listOf(DrawCommand.FillRectangle(firstBounds, ArgbColor(0x80445566.toInt())), DrawCommand.FillRectangle(secondBounds, ArgbColor(0x00ABCDEF)), sample)
        val old = FabricMinecraftFrameInputs.prepare(listOf(FabricMinecraftFrameLayer.Portable(before, bounds)), 1, true)
        val oldMap = checkNotNull(old.portable.single().composition)
        val saved = oldMap.indices.copyArgb()
        val next = FabricMinecraftFrameInputs.prepare(listOf(FabricMinecraftFrameLayer.Portable(after, bounds)), 1, true, old)
        val map = checkNotNull(next.portable.single().composition)
        assertEquals(oldMap.sources.size, map.sources.size)
        assertFalse(old.portable.single().samePreparedAxes(next.portable.single()))
        assertTrue(0L < map.axisEntriesWritten)
        assertArrayEquals(rasterizeHeadlessRegion(after, bounds, 1).copyArgb(), FabricMinecraftCompositionMapTest().compose(map))
        assertArrayEquals(saved, oldMap.indices.copyArgb())
    }

    @Test
    fun anAllRedundantTileFallsBackWithoutRelaxingBalancedClipValidation() {
        val sample = DrawCommand.SampledImage(source, FloatRect(0f, 0f, 128f, 128f), FloatRect(0f, 0f, 64f, 64f), ArgbColor(0x00112233), alphaCutoff = 0f)
        val commands = listOf(DrawCommand.PushClip(bounds), DrawCommand.FillRectangle(bounds, ArgbColor(0x00ABCDEF)), sample, DrawCommand.PopClip)
        assertNull(FabricMinecraftCompositionMap.create(commands, size, 1, IntOffset.Zero, FabricMinecraftSamplingBudget()))
        assertTrue(rasterizeHeadlessRegion(commands, bounds, 1).copyArgb().all { it == 0 })
        assertThrows(IllegalArgumentException::class.java) { FabricMinecraftCompositionMap.create(commands + DrawCommand.PopClip, size, 1, IntOffset.Zero, FabricMinecraftSamplingBudget()) }
        assertThrows(IllegalArgumentException::class.java) { FabricMinecraftCompositionMap.create(commands.dropLast(1), size, 1, IntOffset.Zero, FabricMinecraftSamplingBudget()) }
    }
}
