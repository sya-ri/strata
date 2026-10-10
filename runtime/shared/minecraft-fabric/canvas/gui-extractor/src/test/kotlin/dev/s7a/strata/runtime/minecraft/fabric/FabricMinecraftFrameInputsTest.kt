package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies CPU input reuse independently of native availability, including reload and capacity fallback.
 */
internal class FabricMinecraftFrameInputsTest {
    @Test
    fun stableInputsReuseDescriptionsButResolveAvailabilityOnEveryBorrow() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0x804466AA.toInt(), -1))
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(2f, 2f, 6f, 6f), alphaCutoff = 0f)
        val fill = DrawCommand.FillRectangle(IntRect(0, 0, 8, 8), ArgbColor(0xFF123456.toInt()))
        val commands = listOf(fill, sampled, sampled.copy(destination = FloatRect(4f, 4f, 8f, 8f)))
        for (scale in 1..4) {
            val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(commands, IntSize(8, 8), scale), scale)
            assertSame(inputs, inputs.resolve({ true }) { error("Available images must not fall back") })
            assertEquals(listOf(image), inputs.sampled)
            assertSame(inputs.sampledRequests, inputs.resolve({ true }) { error("No fallback") }.sampledRequests)
            var unavailable = 0
            val fallback =
                inputs.resolve({ false }) {
                    assertSame(image, it)
                    unavailable += 1
                    true
                }
            assertEquals(2, unavailable)
            assertEquals(3, fallback.portable.size)
            assertEquals(0L, fallback.ineligibleSampledImages)
            assertEquals(2L, fallback.capacitySampledImages)
            val unsupported = inputs.resolve({ false }) { false }
            assertEquals(2L, unsupported.ineligibleSampledImages)
            assertEquals(0L, unsupported.capacitySampledImages)
            assertEquals(0L, inputs.capacitySampledImages)
            assertEquals(1, inputs.portable.size)
            assertSame(inputs, inputs.resolve({ true }) { error("Restored storage must be used") })
            assertArrayEquals(
                rasterizeHeadless(listOf(sampled.copy(destination = FloatRect(0f, 0f, 4f, 4f))), IntSize(4, 4), scale).copyArgb(),
                fallback.portable
                    .last()
                    .rasterize()
                    .copyArgb(),
            )
        }
    }

    @Test
    fun directAndComposedTilesShareOneFirstOccurrenceRequestOrder() {
        val size = IntSize(768, 512)
        val first = createDrawImage(IntSize(128, 128)) { _, _ -> 0x804466AA.toInt() }
        val equalPixels = createDrawImage(first.size, first.copyArgb())
        val source = FloatRect(0f, 0f, 128f, 128f)
        val destination = FloatRect(0f, 0f, 768f, 512f)
        val direct = DrawCommand.SampledImage(first, source, FloatRect(0f, 0f, 64f, 64f), alphaCutoff = 0f)
        val composed = DrawCommand.SampledImage(equalPixels, source, destination, tint = ArgbColor(0xC0BFD7EF.toInt()), alphaCutoff = 0f)
        val commands = listOf(direct, composed, composed.copy(image = first), direct.copy(image = equalPixels))
        val layers = partitionFabricMinecraftFrame(commands, size)
        val inputs = FabricMinecraftFrameInputs(layers, 1, compositionEnabled = true)
        assertTrue(1 < inputs.portable.count { it.composition != null })
        assertEquals(2, inputs.sampled.size)
        assertSame(first, inputs.sampled[0])
        assertSame(equalPixels, inputs.sampled[1])
        assertSame(layers, inputs.layers)
        assertSame(inputs, inputs.resolve({ true }) { error("All sources are available") })

        val replaced = createDrawImage(first.size, first.copyArgb())
        val changedCommands = commands.map { if (it === direct) direct.copy(image = replaced) else it }
        val changed = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(changedCommands, size), 1, compositionEnabled = true)
        assertEquals(3, changed.sampled.size)
        assertSame(replaced, changed.sampled[0])
        assertSame(equalPixels, changed.sampled[1])
        assertSame(first, changed.sampled[2])
        val empty = FabricMinecraftFrameInputs(emptyList(), 1, compositionEnabled = true)
        assertTrue(empty.sampled.isEmpty())
        assertSame(first, inputs.sampled[0])
    }

    @Test
    fun repeatedUnavailableDirectPlacementsKeepOccurrenceFallbackCounts() {
        val size = IntSize(64, 64)
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val source = FloatRect(0f, 0f, 1f, 1f)
        for (occurrences in listOf(1, 64, 4096)) {
            val commands =
                List(occurrences) { index ->
                    val x = (index % 64).toFloat()
                    val y = (index / 64).toFloat()
                    DrawCommand.SampledImage(image, source, FloatRect(x, y, x + 1f, y + 1f), alphaCutoff = 0f)
                }
            val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(commands, size), 1)
            assertEquals(1, inputs.sampled.size)
            assertEquals(occurrences, inputs.layers.size)
            assertEquals(occurrences.toLong(), inputs.resolve({ false }) { true }.capacitySampledImages)
            assertEquals(occurrences.toLong(), inputs.resolve({ false }) { false }.ineligibleSampledImages)
            assertSame(inputs, inputs.resolve({ true }) { error("Restored sources must draw directly") })
        }
    }

    @Test
    fun ineligibleCountsRemainAttachedToOriginalPortableRuns() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 2f, 2f), tint = ArgbColor(0x80FFFFFF.toInt()))
        val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(command), IntSize(2, 2)), 1)
        assertEquals(1L, inputs.ineligibleSampledImages)
        assertEquals(0L, inputs.capacitySampledImages)
        assertSame(inputs, inputs.resolve({ error("No direct images") }) { error("No fallback") })
    }
}
