package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verifies pure direct-image eligibility, barriers, clips, and tight fallback localization. */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftFrameLayerTest {
    @Test
    fun eligibleImagesSplitAtTheirExactOrderWhileUnsupportedSamplingStaysPortable() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { 0x80336699.toInt() })
        val direct = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(1.25f, 2.5f, 5.75f, 7.25f), alphaCutoff = 0f)
        val unsupported = direct.copy(source = FloatRect(0.5f, 0f, 3.5f, 4f))
        val platform = DrawCommand.Platform(TestPlatform, IntRect(0, 0, 1, 1))
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(-1)),
                DrawCommand.PushClip(IntRect(1, 2, 8, 9)),
                direct,
                unsupported,
                platform,
                DrawCommand.PopClip,
            )

        val layers = partitionFabricMinecraftFrame(commands, IntSize(10, 10))

        assertEquals(4, layers.size)
        assertEquals(IntRect(0, 0, 2, 2), (layers[0] as FabricMinecraftFrameLayer.Portable).bounds)
        val sampled = layers[1] as FabricMinecraftFrameLayer.Sampled
        assertSame(image, sampled.command.image)
        assertEquals(IntRect(1, 2, 8, 9), sampled.clip)
        assertEquals(IntRect(1, 2, 6, 8), sampled.visibleBounds)
        val portableLayer = layers[2] as FabricMinecraftFrameLayer.Portable
        assertEquals(1, portableLayer.ineligibleSampledImages)
        val portableSampled = portableLayer.commands.filterIsInstance<DrawCommand.SampledImage>().single()
        assertSame(image, portableSampled.image)
        assertEquals(unsupported.source, portableSampled.source)
        assertSame(platform, (layers[3] as FabricMinecraftFrameLayer.Platform).command)
    }

    @Test
    fun directEligibilityIgnoresDestinationPlacementButRejectsUnsupportedSamplingInputs() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { -1 })
        val direct = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(1.25f, 2.5f, 5.75f, 7.25f), alphaCutoff = 0f)

        assertTrue(isDirectFabricSampledImage(direct))
        assertTrue(isDirectFabricSampledImage(direct.copy(destination = FloatRect(-200.5f, 300.25f, -190.25f, 311.75f))))
        assertFalse(isDirectFabricSampledImage(direct.copy(source = FloatRect(0.5f, 0f, 3.5f, 4f))))
        assertFalse(isDirectFabricSampledImage(direct.copy(tint = ArgbColor(0x80FFFFFF.toInt()))))
        assertFalse(isDirectFabricSampledImage(direct.copy(alphaCutoff = 0.5f)))
        assertFalse(isDirectFabricSampledImage(direct.copy(orientation = SampledImageOrientation.FlipHorizontal)))
    }

    @Test
    fun capacityFallbackKeepsFractionalSamplingAndLocalizesTheEffectiveClip() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(3.25f, 4.5f, 8.75f, 9.25f), alphaCutoff = 0f)
        val sampled =
            partitionFabricMinecraftFrame(
                listOf(DrawCommand.PushClip(IntRect(4, 5, 8, 9)), command, DrawCommand.PopClip),
                IntSize(12, 12),
            ).single() as FabricMinecraftFrameLayer.Sampled

        val fallback = portableFabricSampledFallback(sampled)

        assertEquals(IntRect(4, 5, 8, 9), fallback.bounds)
        assertEquals(DrawCommand.PushClip(IntRect(0, 0, 4, 4)), fallback.commands.first())
        assertEquals(
            FloatRect(-0.75f, -0.5f, 4.75f, 4.25f),
            fallback.commands
                .filterIsInstance<DrawCommand.SampledImage>()
                .single()
                .destination,
        )
        assertEquals(DrawCommand.PopClip, fallback.commands.last())
    }

    @Test
    fun modernGuiTextureCoordinatesUseAbsoluteCornersForNonOriginLayers() {
        val coordinates = ArrayList<Int>(4)

        submitFabricMinecraftGuiCorners(IntRect(144, 4, 176, 36)) { x0, y0, x1, y1 ->
            coordinates.addAll(listOf(x0, y0, x1, y1))
        }

        assertEquals(listOf(144, 4, 176, 36), coordinates)
    }

    @Test
    fun orderedSubmissionSeparatesEveryAdjacentLayerWithoutOuterBoundaries() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(1f, 1f, 3f, 3f), alphaCutoff = 0f)
        val layers =
            partitionFabricMinecraftFrame(
                listOf(
                    DrawCommand.FillRectangle(IntRect(0, 0, 4, 4), ArgbColor(-1)),
                    sampled,
                    sampled.copy(destination = FloatRect(2f, 2f, 4f, 4f)),
                    DrawCommand.FillRectangle(IntRect(3, 0, 4, 1), ArgbColor(0xFF00FF00.toInt())),
                    DrawCommand.Platform(TestPlatform, IntRect(0, 0, 1, 1)),
                ),
                IntSize(4, 4),
            )
        assertEquals(5, layers.size)

        val emptyEvents = ArrayList<String>()
        submitFabricMinecraftFrameLayers(emptyList(), { emptyEvents.add("boundary") }) { emptyEvents.add("layer") }
        assertEquals(emptyList<String>(), emptyEvents)

        val singleEvents = ArrayList<String>()
        submitFabricMinecraftFrameLayers(layers.take(1), { singleEvents.add("boundary") }) { singleEvents.add("layer") }
        assertEquals(listOf("layer"), singleEvents)

        val boundary = Any()
        val events = ArrayList<Any>()
        submitFabricMinecraftFrameLayers(layers, { events.add(boundary) }) { layer -> events.add(layer) }
        assertEquals(
            listOf(layers[0], boundary, layers[1], boundary, layers[2], boundary, layers[3], boundary, layers[4]),
            events,
        )
    }

    private data object TestPlatform : PlatformDrawCommand
}
