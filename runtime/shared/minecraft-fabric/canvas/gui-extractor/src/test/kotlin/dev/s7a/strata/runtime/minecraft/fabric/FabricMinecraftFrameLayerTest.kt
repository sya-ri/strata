package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verifies pure direct-image eligibility, barriers, clips, and tight fallback localization. */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftFrameLayerTest {
    @Test
    fun fractionalSourceRequiresFloatingUvSupportAndPreservesOtherEligibilityRules() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0x804466AA.toInt(), -1))
        val command = DrawCommand.SampledImage(image, FloatRect(0.25f, 0.5f, 1.75f, 2f), FloatRect(1f, 1f, 5f, 5f), alphaCutoff = 0f)
        for (scale in 1..4) {
            assertFalse(isDirectFabricSampledImage(command, scale))
            assertTrue(isDirectFabricSampledImage(command, scale, fractionalSource = true))
            val layer = partitionFabricMinecraftFrame(listOf(command), IntSize(8, 8), scale, fractionalSource = true).single() as FabricMinecraftFrameLayer.Sampled
            assertSame(command, layer.command)
            assertFalse(isDirectFabricSampledImage(command.copy(tint = ArgbColor(0x80FFFFFF.toInt())), scale, fractionalSource = true))
            assertFalse(isDirectFabricSampledImage(command.copy(alphaCutoff = 0.1f), scale, fractionalSource = true))
        }
    }

    @Test
    fun invisibleFallbackInputsAreOmittedWithoutMergingDirectImageBarriers() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0x804466AA.toInt(), -1))
        val first = DrawCommand.FillRectangle(IntRect(1, 1, 3, 3), ArgbColor(-1))
        val last = DrawCommand.FillRectangle(IntRect(2, 2, 4, 4), ArgbColor(0x80336699.toInt()))
        val direct = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(20f, 1f, 24f, 5f), alphaCutoff = 0f)
        val unsupported = direct.copy(tint = ArgbColor(0x80FFFFFF.toInt()))
        val viewport = IntSize(10, 10)
        val expected = partitionFabricMinecraftFrame(listOf(first, last), viewport).single() as FabricMinecraftFrameLayer.Portable
        for (scale in 1..4) {
            for (destination in listOf(FloatRect(-4f, 1f, 0f, 5f), FloatRect(10f, 1f, 14f, 5f), FloatRect(1f, -4f, 5f, 0f), FloatRect(1f, 10f, 5f, 14f))) {
                for (sampled in listOf(unsupported.copy(destination = destination), direct.copy(source = FloatRect(0.5f, 0f, 1.5f, 2f), destination = destination))) {
                    val commands = listOf(first, sampled, last)
                    val actual = partitionFabricMinecraftFrame(commands, viewport, scale).single() as FabricMinecraftFrameLayer.Portable
                    assertEquals(expected.bounds, actual.bounds)
                    assertEquals(expected.commands, actual.commands)
                    assertEquals(0, actual.ineligibleSampledImages)
                    assertArrayEquals(rasterizeHeadless(listOf(first, last), viewport, scale).copyArgb(), rasterizeHeadless(commands, viewport, scale).copyArgb())
                }
                val separated = partitionFabricMinecraftFrame(listOf(first, direct.copy(destination = destination), last), viewport, scale)
                assertEquals(2, separated.size)
                assertEquals(IntRect(1, 1, 3, 3), (separated[0] as FabricMinecraftFrameLayer.Portable).bounds)
                assertEquals(IntRect(2, 2, 4, 4), (separated[1] as FabricMinecraftFrameLayer.Portable).bounds)
            }
        }
    }

    @Test
    fun clippedSampledImagesKeepNestedClipBalanceAndVisibleBarriers() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val direct = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(5f, 5f, 7f, 7f), alphaCutoff = 0f)
        val first = DrawCommand.FillRectangle(IntRect(1, 1, 3, 3), ArgbColor(-1))
        val last = first.copy(color = ArgbColor(0x80336699.toInt()))
        val clip = DrawCommand.PushClip(IntRect(0, 0, 4, 4))
        val fractional = DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.25f, 3.75f, 3.75f))
        val prefixes = listOf(listOf(clip), listOf(clip, fractional))
        for (scale in 1..4) {
            for (prefix in prefixes) {
                val suffix = List(prefix.size) { DrawCommand.PopClip }
                val expected = partitionFabricMinecraftFrame(prefix + listOf(first, last) + suffix + direct, IntSize(10, 10), scale)
                for (sampled in listOf(direct.copy(tint = ArgbColor(0x80FFFFFF.toInt())), direct.copy(source = FloatRect(0.5f, 0f, 1.5f, 2f)))) {
                    val actual = partitionFabricMinecraftFrame(prefix + listOf(first, sampled, last) + suffix + direct, IntSize(10, 10), scale)
                    assertEquals(2, actual.size)
                    assertEquals((expected[0] as FabricMinecraftFrameLayer.Portable).commands, (actual[0] as FabricMinecraftFrameLayer.Portable).commands)
                    assertEquals(direct, (actual[1] as FabricMinecraftFrameLayer.Sampled).command)
                }
                val separated = partitionFabricMinecraftFrame(prefix + listOf(first, direct, last) + suffix + direct, IntSize(10, 10), scale)
                assertEquals(3, separated.size)
                assertEquals(direct, (separated[2] as FabricMinecraftFrameLayer.Sampled).command)
            }
        }
    }

    @Test
    fun distantFractionalEdgesAreBoundedBeforeIntegerEnvelopeConversion() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(1f, 1f, 2f, 2f), alphaCutoff = 0f)
        val layers =
            partitionFabricMinecraftFrame(
                listOf(DrawCommand.PushFractionalClip(FloatRect(-1e20f, -1e20f, 1e20f, 1e20f)), command, DrawCommand.PopClip),
                IntSize(10, 10),
            )
        assertEquals(IntRect(0, 0, 10, 10), (layers.single() as FabricMinecraftFrameLayer.Sampled).clip)
    }

    @Test
    fun integerInnerClipCanResolveAFractionalOuterClipBeforeNativeSubmission() {
        val platform = DrawCommand.Platform(TestPlatform, IntRect(0, 0, 10, 10))
        val outer = DrawCommand.PushFractionalClip(FloatRect(0.5f, 0.5f, 8.5f, 8.5f))
        val inner = DrawCommand.PushClip(IntRect(1, 1, 8, 8))
        val layers = partitionFabricMinecraftFrame(listOf(outer, inner, platform, DrawCommand.PopClip, DrawCommand.PopClip), IntSize(10, 10))
        assertEquals(IntRect(1, 1, 8, 8), (layers.single() as FabricMinecraftFrameLayer.Platform).clip)
        assertThrows(IllegalArgumentException::class.java) {
            partitionFabricMinecraftFrame(listOf(outer, platform, DrawCommand.PopClip), IntSize(10, 10))
        }
    }

    @Test
    fun fractionalClipKeepsInteriorImagesDirectAndPreservesBoundarySamplingInFallback() {
        val image = createDrawImage(IntSize(3, 3), IntArray(9) { -1 })
        val edge = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 3f, 3f), FloatRect(0f, 0f, 3f, 3f), alphaCutoff = 0f)
        val interior = edge.copy(destination = FloatRect(2f, 2f, 5f, 5f))
        val clip = DrawCommand.PushFractionalClip(FloatRect(0.5f, 0.5f, 8.5f, 8.5f))
        val layers = partitionFabricMinecraftFrame(listOf(clip, edge, interior, DrawCommand.PopClip, edge), IntSize(10, 10), scale = 2)
        assertEquals(3, layers.size)
        val portable = layers[0] as FabricMinecraftFrameLayer.Portable
        assertEquals(clip, portable.commands.first())
        assertTrue(portable.absoluteCoordinates)
        assertEquals(edge, portable.commands.filterIsInstance<DrawCommand.SampledImage>().single())
        assertEquals(interior, (layers[1] as FabricMinecraftFrameLayer.Sampled).command)
        assertEquals(edge, (layers[2] as FabricMinecraftFrameLayer.Sampled).command)
    }

    @Test
    fun eligibleImagesSplitAtTheirExactOrderWhileUnsupportedSamplingStaysPortable() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { 0x80336699.toInt() })
        val direct = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(1.25f, 2.25f, 5.25f, 6.25f), alphaCutoff = 0f)
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
        assertEquals(IntRect(1, 2, 6, 7), sampled.visibleBounds)
        val portableLayer = layers[2] as FabricMinecraftFrameLayer.Portable
        assertEquals(1, portableLayer.ineligibleSampledImages)
        val portableSampled = portableLayer.commands.filterIsInstance<DrawCommand.SampledImage>().single()
        assertSame(image, portableSampled.image)
        assertEquals(unsupported.source, portableSampled.source)
        assertSame(platform, (layers[3] as FabricMinecraftFrameLayer.Platform).command)
    }

    @Test
    fun directEligibilityPreservesFloatingPlacementExceptPhysicalPixelCenterEdges() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { -1 })
        val direct = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(1.25f, 2.25f, 5.25f, 6.25f), alphaCutoff = 0f)

        assertTrue(isDirectFabricSampledImage(direct))
        assertTrue(isDirectFabricSampledImage(direct.copy(destination = FloatRect(-200.25f, 300.25f, -190.25f, 311.75f))))
        assertFalse(isDirectFabricSampledImage(direct, scale = 2))
        assertTrue(isDirectFabricSampledImage(direct, scale = 3))
        assertTrue(isDirectFabricSampledImage(direct, scale = 4))
        assertFalse(isDirectFabricSampledImage(direct.copy(destination = FloatRect(1f, 2.5f, 5f, 7f))))
        assertFalse(isDirectFabricSampledImage(direct.copy(source = FloatRect(0.5f, 0f, 3.5f, 4f))))
        assertFalse(isDirectFabricSampledImage(direct.copy(tint = ArgbColor(0x80FFFFFF.toInt()))))
        assertFalse(isDirectFabricSampledImage(direct.copy(alphaCutoff = 0.5f)))
        assertFalse(isDirectFabricSampledImage(direct.copy(orientation = SampledImageOrientation.FlipHorizontal)))
    }

    @Test
    fun fractionalClipsUseDirectImagesOnlyWhenNativeScissorsPreserveEveryPhysicalPixel() {
        val viewport = IntSize(10, 10)
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { index -> (index * 0x112233) or 0x80000000.toInt() })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 10f, 10f), alphaCutoff = 0f)
        val edges = listOf(-100f, 0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f, 1.25f, 1.5f)
        for (scale in 1..4) {
            for (left in edges) {
                for (right in edges.map { 8f + it.coerceAtLeast(0f) }) {
                    val clip = FloatRect(left, 0.25f, right, 8.75f)
                    val layers = partitionFabricMinecraftFrame(listOf(DrawCommand.PushFractionalClip(clip), command, DrawCommand.PopClip), viewport, scale)
                    val sampled = layers.singleOrNull() as? FabricMinecraftFrameLayer.Sampled
                    if (scale == 1) assertTrue(sampled != null, "Scale-one clip must use the GPU: $clip")
                    if (sampled == null) continue
                    assertSame(command, sampled.command)
                    val nativeClip = checkNotNull(sampled.clip)
                    for (physicalY in 0 until viewport.height * scale) {
                        for (physicalX in 0 until viewport.width * scale) {
                            val centerX = (physicalX + 0.5) / scale
                            val centerY = (physicalY + 0.5) / scale
                            val original = clip.left <= centerX && centerX < clip.right && clip.top <= centerY && centerY < clip.bottom
                            val native = nativeClip.left <= centerX && centerX < nativeClip.right && nativeClip.top <= centerY && centerY < nativeClip.bottom
                            assertEquals(original, native, "Clip $clip at scale $scale changed physical pixel ($physicalX, $physicalY)")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nestedFractionalAndIntegerClipsResolveTogetherWithoutRoundingEachAncestorOutwards() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x80FF0000.toInt(), 0, 0xFF0000FF.toInt()))
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 10f, 10f), alphaCutoff = 0f)
        val commands =
            listOf(
                DrawCommand.PushFractionalClip(FloatRect(0.1f, 0.1f, 9.9f, 9.9f)),
                DrawCommand.PushClip(IntRect(1, 1, 9, 9)),
                DrawCommand.PushFractionalClip(FloatRect(1.1f, 1.1f, 8.9f, 8.9f)),
                command,
                DrawCommand.PopClip,
                DrawCommand.PopClip,
                DrawCommand.PopClip,
            )
        for (scale in 1..4) {
            val sampled = partitionFabricMinecraftFrame(commands, IntSize(10, 10), scale).single() as FabricMinecraftFrameLayer.Sampled
            assertEquals(IntRect(1, 1, 9, 9), sampled.clip)
            assertSame(command, sampled.command)
        }
        assertThrows(IllegalArgumentException::class.java) { partitionFabricMinecraftFrame(commands, IntSize(10, 10), 0) }
    }

    @Test
    fun capacityFallbackKeepsOriginalFractionalSamplingAndEffectiveClip() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(3.25f, 4.25f, 8.75f, 8.25f), alphaCutoff = 0f)
        val sampled =
            partitionFabricMinecraftFrame(
                listOf(DrawCommand.PushClip(IntRect(4, 5, 8, 9)), command, DrawCommand.PopClip),
                IntSize(12, 12),
            ).single() as FabricMinecraftFrameLayer.Sampled

        val fallback = portableFabricSampledFallback(sampled)

        assertEquals(IntRect(4, 5, 8, 9), fallback.bounds)
        assertTrue(fallback.absoluteCoordinates)
        assertEquals(DrawCommand.PushClip(IntRect(4, 5, 8, 9)), fallback.commands.first())
        assertEquals(
            command.destination,
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
