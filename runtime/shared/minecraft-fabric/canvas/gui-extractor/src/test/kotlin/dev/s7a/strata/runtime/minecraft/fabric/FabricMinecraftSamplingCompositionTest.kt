@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Checks independent effect admission without changing overlapping CPU composition or retaining proof history. */
internal class FabricMinecraftSamplingCompositionTest {
    private val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x807195B3.toInt(), 0, -1))
    private val sample = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(1f, 1f, 5f, 5f), ArgbColor(0xFF00FFFF.toInt()), alphaCutoff = 1f)

    @Test
    fun disjointMasksOverOpaqueFillsRetainExactGpuPresentation() {
        val background = DrawCommand.FillRectangle(IntRect(0, 0, 256, 144), ArgbColor(0xFF7195B3.toInt()))
        val masks = List(40) { index -> sample.copy(destination = FloatRect(index % 8 * 16f, index / 8 * 12f, index % 8 * 16f + 12f, index / 8 * 12f + 8f)) }
        val layers = partitionFabricMinecraftFrame(listOf(background) + masks, IntSize(256, 144), exactSampling = true)
        assertEquals(40, layers.count { it is FabricMinecraftFrameLayer.Sampled })
    }

    @Test
    fun earlierAndLaterOverlappingLayersKeepTheOriginalCpuRun() {
        val rectangle = DrawCommand.FillRectangle(IntRect(2, 2, 6, 6), ArgbColor(0x807195B3.toInt()))
        val shadow = sample.copy(destination = FloatRect(2f, 2f, 6f, 6f), tint = ArgbColor(0xFF123456.toInt()))
        val blit = DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(2, 2, 6, 6))
        val pixels = DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(2, 2, 6, 6))
        for (selected in listOf(sample, sample.copy(tint = ArgbColor(-1)))) {
            for (other in listOf(rectangle, shadow, blit, pixels, selected)) {
                for (commands in listOf(listOf(other, selected), listOf(selected, other))) {
                    val layers = partitionFabricMinecraftFrame(commands, IntSize(8, 8), exactSampling = true)
                    assertTrue(layers.all { it is FabricMinecraftFrameLayer.Portable })
                    assertEquals(commands, (layers.single() as FabricMinecraftFrameLayer.Portable).commands)
                }
            }
        }
    }

    @Test
    fun clipsAndNativeBarriersDoNotHideOverlappingComposition() {
        val platform = DrawCommand.Platform(TestPlatform, IntRect(2, 2, 6, 6))
        assertFalse(FabricMinecraftSamplingComposition(listOf(sample, platform)).admits(0, sample))
        val touching = platform.copy(bounds = IntRect(5, 1, 7, 5))
        assertTrue(FabricMinecraftSamplingComposition(listOf(sample, touching)).admits(0, sample))
        val commands =
            listOf(
                DrawCommand.PushClip(IntRect(0, 0, 4, 4)),
                sample.copy(tint = ArgbColor(0xFF123456.toInt())),
                DrawCommand.PopClip,
                touching,
                DrawCommand.PushFractionalClip(FloatRect(4f, 4f, 8f, 8f)),
                sample,
                DrawCommand.PopClip,
            )
        assertFalse(FabricMinecraftSamplingComposition(commands).admits(5, sample))
    }

    @Test
    fun boundedProofExhaustionFallsBackAndANewFrameStartsWithoutHistory() {
        val commands = List(8193) { DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(-1)) } + sample
        val proof = FabricMinecraftSamplingComposition(commands)
        assertFalse(proof.admits(commands.lastIndex, sample))
        assertFalse(proof.admits(commands.lastIndex, sample))
        assertTrue(FabricMinecraftSamplingComposition(listOf(sample)).admits(0, sample))
    }

    private data object TestPlatform : PlatformDrawCommand
}
