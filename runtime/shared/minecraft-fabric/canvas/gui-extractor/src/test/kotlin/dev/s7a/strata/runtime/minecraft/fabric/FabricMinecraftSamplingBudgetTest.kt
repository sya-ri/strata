package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks image-count and aggregate GPU byte limits before allocation, including complete CPU fallback.
 */
internal class FabricMinecraftSamplingBudgetTest {
    @Test
    fun lookupBytesCountTowardTheLimitAndRejectedAllocationConsumesNothing() {
        val budget = FabricMinecraftSamplingBudget()
        val clip = IntRect(0, 0, 4096, 4096)
        assertFalse(budget.admit(FloatRect(0f, 0f, 4096f, 4096f), clip, 1))
        assertTrue(budget.admit(FloatRect(0f, 0f, 4096f, 4093f), clip, 1))
        assertFalse(budget.admit(FloatRect(0f, 0f, 1f, 1f), clip, 1))
        assertTrue(budget.admit(FloatRect(5000f, 5000f, 5001f, 5001f), clip, 1))
    }

    @Test
    fun excessExactOutputsFallBackWithoutDroppingCommands() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 1f, 1f), alphaCutoff = 0f)
        val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(List(257) { command }, IntSize(1, 1), exactSampling = true), 1)
        assertEquals(256, inputs.sampled.size)
        val resolved = inputs.resolve { false }
        assertTrue(resolved.sampled.isEmpty())
        assertEquals(257, resolved.portable.size)
    }

    @Test
    fun oversizedEffectsAndUnsupportedClipsUsePortableImages() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 1f, 1f), ArgbColor(0xFF00FFFF.toInt()), alphaCutoff = 1f)
        for (tint in listOf(command.tint, ArgbColor(-1))) {
            val selected = command.copy(tint = tint)
            val oversized = selected.copy(destination = FloatRect(0f, 0f, 4096f, 4096f))
            val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(oversized), IntSize(4096, 4096), exactSampling = true), 1)
            assertEquals(0, inputs.sampled.size)
            val admitted = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(selected), IntSize(1, 1), exactSampling = true), 1)
            val resolved = admitted.resolve { false }
            assertTrue(resolved.sampled.isEmpty())
            assertEquals(1, resolved.portable.size)
        }
        val large = command.copy(destination = FloatRect(0f, 0f, 600f, 450f))
        val clips = listOf(DrawCommand.PushFractionalClip(FloatRect(0.5f, 0.5f, 599.5f, 449.5f)), large, DrawCommand.PopClip)
        val clipped = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(clips, IntSize(600, 450), scale = 2, exactSampling = true), 2)
        assertTrue(clipped.sampled.isEmpty())
        assertTrue(clipped.portable.isNotEmpty())
    }

    @Test
    fun translucentEffectsRetainTheCompleteCpuPath() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { 0x807195B3.toInt() })
        for (tint in listOf(ArgbColor(-1), ArgbColor(0xFF00FFFF.toInt()))) {
            val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 1f, 1f), tint, alphaCutoff = 0.1f)
            val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(command), IntSize(1, 1), exactSampling = true), 1)
            assertTrue(inputs.sampled.isEmpty())
            assertEquals(1, inputs.portable.size)
        }
    }
}
