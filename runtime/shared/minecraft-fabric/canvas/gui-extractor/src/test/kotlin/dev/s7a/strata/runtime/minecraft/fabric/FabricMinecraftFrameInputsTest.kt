package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies CPU input reuse independently of native availability, including reload and capacity fallback.
 */
@OptIn(InternalStrataRuntimeApi::class)
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
            assertEquals(listOf(image, image), inputs.sampled)
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
    fun ineligibleCountsRemainAttachedToOriginalPortableRuns() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 2f, 2f), tint = ArgbColor(0x80FFFFFF.toInt()))
        val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(command), IntSize(2, 2)), 1)
        assertEquals(1L, inputs.ineligibleSampledImages)
        assertEquals(0L, inputs.capacitySampledImages)
        assertSame(inputs, inputs.resolve({ error("No direct images") }) { error("No fallback") })
    }

    @Test
    fun partialAlphaRetainsTheCompleteOriginalCompositionAtEveryGuiScale() {
        val size = IntSize(8, 4)
        val image = createDrawImage(IntSize(4, 1), intArrayOf(0x00123456, 0x014A1A8E, 0x7F4A1A8E, 0xFE4A1A8E.toInt()))
        val background = DrawCommand.FillRectangle(IntRect(0, 0, 8, 4), ArgbColor(0xFF112233.toInt()))
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 1f), FloatRect(0f, 0f, 4f, 1f), alphaCutoff = 0f)
        val foreground = DrawCommand.FillRectangle(IntRect(6, 2, 8, 4), ArgbColor(0xFFABCDEF.toInt()))
        val commands = listOf(background, sampled, foreground)
        for (scale in 1..4) {
            val inputs = FabricMinecraftFrameInputs.prepare(commands, size, scale)
            assertEquals(3, inputs.layers.size)
            val resolved = inputs.resolve({ true }, { error("Available sources must not query unsupported fallback") }, { true })
            assertEquals(1, resolved.layers.size)
            assertTrue(resolved.layers.single() is FabricMinecraftFrameLayer.Portable)
            assertEquals(0L, resolved.capacitySampledImages)
            assertEquals(1L, resolved.ineligibleSampledImages)
            assertTrue(resolved.portable.all { it.sampling == null })
            val expected = rasterizeHeadless(commands, size, scale)
            assertEquals(0xFF2D1E60.toInt(), expected.argbAt(2 * scale, 0))
            assertArrayEquals(
                expected.copyArgb(),
                resolved.portable
                    .single()
                    .rasterize()
                    .copyArgb(),
            )
            assertSame(resolved, inputs.resolve({ true }, { error("No capacity failure") }, { true }))
        }
    }

    @Test
    fun binaryAlphaAndOpaqueMasksPreserveTheirDirectAdmission() {
        val size = IntSize(8, 4)
        val binary = createDrawImage(IntSize(2, 1), intArrayOf(0x00123456, -1))
        val direct = DrawCommand.SampledImage(binary, FloatRect(0f, 0f, 2f, 1f), FloatRect(0f, 0f, 8f, 4f), alphaCutoff = 0f)
        val inputs = FabricMinecraftFrameInputs.prepare(listOf(direct), size, 1, exactSampling = true)
        assertSame(inputs, inputs.resolve({ true }, { error("Available source") }, { false }))
        assertEquals(null, (inputs.layers.single() as FabricMinecraftFrameLayer.Sampled).sampling)
        val partial = createDrawImage(IntSize(2, 1), intArrayOf(0x7F4A1A8E, -1))
        val masked = direct.copy(image = partial, source = FloatRect(0.125f, 0f, 1.875f, 1f), tint = ArgbColor(0xFFFF00FF.toInt()), alphaCutoff = 1f)
        val opaque = FabricMinecraftFrameInputs.prepare(listOf(masked), size, 1, fractionalSource = true, exactSampling = true)
        assertSame(opaque, opaque.resolve({ true }, { error("Available mask source") }, { true }))
        assertTrue((opaque.layers.single() as FabricMinecraftFrameLayer.Sampled).sampling != null)
        assertEquals(0L, opaque.capacitySampledImages)
        assertEquals(0L, opaque.ineligibleSampledImages)
    }

    @Test
    fun unavailableFirstUploadRetainsBackgroundAndOriginalCapacityAccounting() {
        val size = IntSize(4, 2)
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 4, 2), ArgbColor(0xFF112233.toInt())),
                DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 4f, 2f), alphaCutoff = 0f),
            )
        for (scale in 1..4) {
            val inputs = FabricMinecraftFrameInputs.prepare(commands, size, scale)
            val fallback = inputs.resolve({ false }, { true }, { error("An unavailable upload has no alpha metadata") })
            assertEquals(1, fallback.portable.size)
            assertEquals(1L, fallback.capacitySampledImages)
            assertEquals(0L, fallback.ineligibleSampledImages)
            assertArrayEquals(
                rasterizeHeadless(commands, size, scale).copyArgb(),
                fallback.portable
                    .single()
                    .rasterize()
                    .copyArgb(),
            )
            assertSame(fallback, inputs.resolve({ false }, { true }, { error("No source upload") }))
        }
    }

    @Test
    fun currentFallbackReplacesOnAvailabilityChangesAndSurvivesAFailedBorrow() {
        val size = IntSize(4, 2)
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 4f, 2f), alphaCutoff = 0f)
        val commands = listOf(DrawCommand.FillRectangle(IntRect(0, 0, 4, 2), ArgbColor(0xFF112233.toInt())), sampled)
        val inputs = FabricMinecraftFrameInputs.prepare(commands, size, 1)
        val uploaded = inputs.resolve({ true }, { true }, { true })
        val reloaded = inputs.resolve({ false }, { true }, { error("Reloaded storage is absent") })
        assertNotSame(uploaded, reloaded)
        assertEquals(1L, reloaded.capacitySampledImages)
        val restored = inputs.resolve({ true }, { true }, { true })
        assertNotSame(reloaded, restored)
        assertSame(restored, inputs.resolve({ true }, { true }, { true }))
        val unsupported = inputs.resolve({ false }, { false }, { error("Unsupported storage is absent") })
        assertEquals(0L, unsupported.capacitySampledImages)
        assertEquals(1L, unsupported.ineligibleSampledImages)
        assertThrows(IllegalStateException::class.java) {
            inputs.resolve({ error("Original upload failure") }, { true }, { true })
        }
        assertSame(unsupported, inputs.resolve({ false }, { false }, { error("Unavailable source") }))
        val replacement = FabricMinecraftFrameInputs.prepare(commands, size, 1)
        assertNotSame(restored, replacement.resolve({ true }, { true }, { true }))
        assertArrayEquals(
            restored.portable
                .single()
                .rasterize()
                .copyArgb(),
            replacement
                .resolve({ true }, { true }, { true })
                .portable
                .single()
                .rasterize()
                .copyArgb(),
        )
    }

    @Test
    fun completeFallbackPreservesNestedClipsAndOpaquePlatformBarriers() {
        val size = IntSize(8, 4)
        val partial = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val binary = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val first =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 8, 4), ArgbColor(0xFF112233.toInt())),
                DrawCommand.PushFractionalClip(FloatRect(0.5f, 0.5f, 7.5f, 3.5f)),
                DrawCommand.PushClip(IntRect(1, 1, 7, 3)),
                DrawCommand.SampledImage(binary, FloatRect(0f, 0f, 1f, 1f), FloatRect(1f, 1f, 7f, 3f), alphaCutoff = 0f),
                DrawCommand.SampledImage(partial, FloatRect(0f, 0f, 1f, 1f), FloatRect(1f, 1f, 7f, 3f), alphaCutoff = 0f),
                DrawCommand.PopClip,
                DrawCommand.PopClip,
            )
        val platform = DrawCommand.Platform(TestPlatform, IntRect(0, 0, 8, 4))
        val last =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 8, 4), ArgbColor(0xFF445566.toInt())),
                DrawCommand.SampledImage(partial, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 8f, 4f), alphaCutoff = 0f),
            )
        for (scale in 1..4) {
            val inputs = FabricMinecraftFrameInputs.prepare(first + platform + last, size, scale)
            val resolved = inputs.resolve({ true }, { true }, { it === partial })
            assertEquals(3, resolved.layers.size)
            assertTrue(resolved.layers[0] is FabricMinecraftFrameLayer.Portable)
            assertSame(platform, (resolved.layers[1] as FabricMinecraftFrameLayer.Platform).command)
            assertTrue(resolved.layers[2] is FabricMinecraftFrameLayer.Portable)
            assertArrayEquals(
                rasterizeHeadless(first, size, scale).copyArgb(),
                resolved.portable
                    .first()
                    .rasterize()
                    .copyArgb(),
            )
            assertArrayEquals(
                rasterizeHeadless(last, size, scale).copyArgb(),
                resolved.portable
                    .last()
                    .rasterize()
                    .copyArgb(),
            )
        }
    }

    private data object TestPlatform : PlatformDrawCommand
}
