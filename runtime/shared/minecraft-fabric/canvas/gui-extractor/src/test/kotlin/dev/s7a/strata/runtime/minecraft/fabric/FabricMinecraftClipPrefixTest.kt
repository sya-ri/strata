package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
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
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Checks complete-stack clip values, raw fractional pixel coverage, source mapping and invocation-local prefix ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftClipPrefixTest {
    @Test
    fun deepMixedStacksKeepAllPrimitivePixelsAndOrderedLayerDescriptions() {
        val viewport = IntSize(16, 12)
        for (depth in listOf(0, 1, 4, 32, 128)) {
            for (pattern in ClipPattern.entries) {
                val clips = clips(depth, pattern, viewport)
                val expectedClip = completeClip(clips, viewport)
                val commands = mixedCommands(clips, viewport)
                for (scale in 1..4) {
                    val layers = partitionFabricMinecraftFrame(commands, viewport, scale)
                    assertEquals(5, layers.size)
                    val first = layers[0] as FabricMinecraftFrameLayer.Portable
                    val sampled = layers[1] as FabricMinecraftFrameLayer.Sampled
                    val middle = layers[2] as FabricMinecraftFrameLayer.Portable
                    val platform = layers[3] as FabricMinecraftFrameLayer.Platform
                    val last = layers[4] as FabricMinecraftFrameLayer.Portable
                    assertEquals(expectedClip, first.bounds)
                    assertEquals(expectedClip.takeIf { 0 < depth }, sampled.clip)
                    assertEquals(expectedClip.takeIf { 0 < depth }, platform.clip)
                    assertEquals(IntRect(4, 5, 8, 9), sampled.visibleBounds)
                    assertEquals(IntRect(10, 5, 14, 9), middle.bounds)
                    assertEquals(IntRect(11, 3, 12, 4), last.bounds)
                    assertSame(commands.filterIsInstance<DrawCommand.SampledImage>().single(), sampled.command)
                    assertSame(commands.filterIsInstance<DrawCommand.Platform>().single(), platform.command)
                    val restored = globalCommands(first)
                    for (original in commands.filterIsInstance<DrawCommand.BlitImage>()) {
                        val actual = restored.filterIsInstance<DrawCommand.BlitImage>().single()
                        assertSame(original.image, actual.image)
                        assertEquals(original.source, actual.source)
                        assertEquals(original.destination, actual.destination)
                    }
                    for (original in commands.filterIsInstance<DrawCommand.BlitImagePixels>()) {
                        val actual = restored.filterIsInstance<DrawCommand.BlitImagePixels>().single()
                        assertSame(original.image, actual.image)
                        assertEquals(original.source, actual.source)
                        assertEquals(original.destination, actual.destination)
                    }
                    assertTrue(
                        layers.filterIsInstance<FabricMinecraftFrameLayer.Portable>().all {
                            it.ineligibleSampledImages == 0 && it.capacitySampledImages == 0 && it.tintFallbackImages == 0 && it.alphaCutoffFallbackImages == 0
                        },
                    )
                    assertArrayEquals(
                        rasterizeHeadless(commands.map(::portablePlatform), viewport, scale).copyArgb(),
                        rasterizeHeadless(reconstruct(layers), viewport, scale).copyArgb(),
                        "Depth $depth, $pattern, scale $scale changed pixels or source coordinates.",
                    )
                }
            }
        }
    }

    @Test
    fun rawFractionalPixelCentersStillRejectConservativeIntegerScissors() {
        val viewport = IntSize(16, 12)
        val color = 0xFF336699.toInt()
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { color })
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(0f, 0f, 16f, 12f), alphaCutoff = 0f)
        val raw = DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.25f, 15.75f, 11.75f))
        for (depth in listOf(1, 4, 32, 128)) {
            val commands =
                buildList {
                    repeat(depth - 1) { add(DrawCommand.PushClip(IntRect(0, 0, 16, 12))) }
                    add(raw)
                    add(sampled)
                    repeat(depth) { add(DrawCommand.PopClip) }
                }
            for (scale in 1..4) {
                val layers = partitionFabricMinecraftFrame(commands, viewport, scale)
                if (scale == 1) {
                    assertEquals(IntRect(0, 0, 16, 12), (layers.single() as FabricMinecraftFrameLayer.Sampled).clip)
                } else {
                    val fallback = layers.single() as FabricMinecraftFrameLayer.Portable
                    assertTrue(fallback.absoluteCoordinates)
                    assertEquals(1, fallback.ineligibleSampledImages)
                    assertEquals(0, fallback.capacitySampledImages)
                    assertSame(raw, fallback.commands.filterIsInstance<DrawCommand.PushFractionalClip>().single())
                    assertSame(sampled, fallback.commands.filterIsInstance<DrawCommand.SampledImage>().single())
                }
                val pixels = rasterizeHeadless(reconstruct(layers), viewport, scale)
                for (y in 0 until pixels.size.height) {
                    for (x in 0 until pixels.size.width) {
                        val centerX = (x + 0.5) / scale
                        val centerY = (y + 0.5) / scale
                        val covered = raw.bounds.left <= centerX && centerX < raw.bounds.right && raw.bounds.top <= centerY && centerY < raw.bounds.bottom
                        assertEquals(if (covered) color else 0, pixels.argbAt(x, y))
                    }
                }
            }
        }
    }

    @Test
    fun siblingPopsRestoreEarlierClipsAndEmptyBoundsKeepTheirExactEdges() {
        val viewport = IntSize(16, 12)
        val payload = DrawCommand.Platform(TestPlatform(ArgbColor(-1)), IntRect(3, 3, 4, 4))
        val outer = DrawCommand.PushClip(IntRect(1, 1, 15, 11))
        val inner = DrawCommand.PushClip(IntRect(4, 3, 8, 7))
        val far = DrawCommand.PushClip(IntRect(Int.MAX_VALUE - 4, 2, Int.MAX_VALUE, 6))
        val commands = listOf(outer, payload, inner, payload, DrawCommand.PopClip, payload, far, payload, DrawCommand.PopClip, payload, DrawCommand.PopClip, payload)
        val layers = partitionFabricMinecraftFrame(commands, viewport).filterIsInstance<FabricMinecraftFrameLayer.Platform>()
        assertEquals(
            listOf(IntRect(1, 1, 15, 11), IntRect(4, 3, 8, 7), IntRect(1, 1, 15, 11), IntRect(Int.MAX_VALUE - 4, 2, Int.MAX_VALUE - 4, 6), IntRect(1, 1, 15, 11), null),
            layers.map { it.clip },
        )
        assertSame(layers[0].clip, layers[2].clip)
        assertSame(layers[0].clip, layers[4].clip)
        for (clip in listOf(IntRect(4, 4, 4, 5), IntRect(Int.MIN_VALUE + 1, 2, Int.MIN_VALUE + 4, 5), IntRect(3, 20, 8, 30))) {
            val actual = partitionFabricMinecraftFrame(listOf(DrawCommand.PushClip(clip), payload, DrawCommand.PopClip), viewport).single() as FabricMinecraftFrameLayer.Platform
            assertEquals(completeClip(listOf(DrawCommand.PushClip(clip)), viewport), actual.clip)
        }
    }

    @Test
    fun siblingPopsReuseEarlierPrefixesWithoutChangingPreviousResults() {
        val viewport = IntSize(512, 512)
        val payload = DrawCommand.Platform(TestPlatform(ArgbColor(-1)), IntRect(256, 256, 257, 257))
        for (depth in listOf(1, 4, 32, 128)) {
            val commands =
                buildList {
                    repeat(depth) { index ->
                        val inset = index + 1
                        add(DrawCommand.PushClip(IntRect(inset, inset, 512 - inset, 512 - inset)))
                        add(payload)
                        add(payload)
                    }
                    repeat(depth) {
                        add(DrawCommand.PopClip)
                        add(payload)
                    }
                }
            val first = partitionFabricMinecraftFrame(commands, viewport).filterIsInstance<FabricMinecraftFrameLayer.Platform>()
            val before = first.map { it.clip }
            repeat(depth) { index ->
                val inset = index + 1
                assertEquals(IntRect(inset, inset, 512 - inset, 512 - inset), first[index * 2].clip)
                assertSame(first[index * 2].clip, first[index * 2 + 1].clip)
            }
            repeat(depth - 1) { index ->
                assertSame(first[(depth - index - 2) * 2].clip, first[depth * 2 + index].clip)
            }
            assertEquals(null, first.last().clip)
            val independent = partitionFabricMinecraftFrame(listOf(payload), IntSize(300, 300)).single() as FabricMinecraftFrameLayer.Platform
            assertEquals(null, independent.clip)
            assertEquals(before, first.map { it.clip })
        }
    }

    @Test
    fun largeRunsKeepRowMajorTilesAndTheirOriginalOrderingGroups() {
        val viewport = IntSize(768, 768)
        val firstFill = DrawCommand.FillRectangle(IntRect(0, 0, 768, 768), ArgbColor(0xFF112233.toInt()))
        val secondFill = firstFill.copy(color = ArgbColor(0xFF445566.toInt()))
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { -1 })
        val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(4f, 4f, 8f, 8f), alphaCutoff = 0f)
        val platform = DrawCommand.Platform(TestPlatform(ArgbColor(-1)), IntRect(2, 2, 3, 3))
        val commands =
            buildList {
                repeat(32) { add(DrawCommand.PushClip(IntRect(0, 0, 768, 768))) }
                add(firstFill)
                add(sampled)
                add(secondFill)
                add(platform)
                repeat(32) { add(DrawCommand.PopClip) }
            }
        val layers = partitionFabricMinecraftFrame(commands, viewport)
        assertEquals(20, layers.size)
        val first = layers.take(9).map { it as FabricMinecraftFrameLayer.Portable }
        val second = layers.subList(10, 19).map { it as FabricMinecraftFrameLayer.Portable }
        val expected = (0 until 3).flatMap { row -> (0 until 3).map { column -> IntRect(column * 256, row * 256, (column + 1) * 256, (row + 1) * 256) } }
        assertEquals(expected, first.map { it.bounds })
        assertEquals(expected, second.map { it.bounds })
        val firstGroup = checkNotNull(first.first().orderingGroup)
        val secondGroup = checkNotNull(second.first().orderingGroup)
        assertNotSame(firstGroup, secondGroup)
        first.forEach {
            assertSame(firstGroup, it.orderingGroup)
            assertSame(firstFill, it.commands.filterIsInstance<DrawCommand.FillRectangle>().single())
        }
        second.forEach {
            assertSame(secondGroup, it.orderingGroup)
            assertSame(secondFill, it.commands.filterIsInstance<DrawCommand.FillRectangle>().single())
        }
        assertSame(sampled, (layers[9] as FabricMinecraftFrameLayer.Sampled).command)
        assertSame(platform, (layers.last() as FabricMinecraftFrameLayer.Platform).command)
        var advances = 0
        val submitted = ArrayList<FabricMinecraftFrameLayer>()
        submitFabricMinecraftFrameLayers(layers, { advances += 1 }, submitted::add)
        assertEquals(3, advances)
        assertEquals(layers, submitted)
    }

    @Test
    fun malformedFramesReleaseTheirLocalClipStateBeforeIndependentInvocations() {
        val viewport = IntSize(8, 8)
        val payload = DrawCommand.Platform(TestPlatform(ArgbColor(-1)), IntRect(2, 2, 3, 3))
        val integer = DrawCommand.PushClip(IntRect(1, 1, 7, 7))
        val fractional = DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.25f, 7.75f, 7.75f))
        for (commands in listOf(listOf(DrawCommand.PopClip), listOf(integer, payload), listOf(fractional, payload), listOf(integer, payload, DrawCommand.PopClip, DrawCommand.PopClip))) {
            assertThrows(IllegalArgumentException::class.java) { partitionFabricMinecraftFrame(commands, viewport) }
            val independent = partitionFabricMinecraftFrame(listOf(payload), IntSize(4, 4)).single() as FabricMinecraftFrameLayer.Platform
            assertEquals(null, independent.clip)
            assertSame(payload, independent.command)
        }
    }

    private fun clips(
        depth: Int,
        pattern: ClipPattern,
        viewport: IntSize,
    ): List<DrawCommand> =
        List(depth) { index ->
            val inset = index % 3
            if (pattern == ClipPattern.Integer || (pattern == ClipPattern.Mixed && index % 2 == 0)) {
                DrawCommand.PushClip(IntRect(inset, inset, viewport.width - inset, viewport.height - inset))
            } else {
                val edge = inset + 0.25f
                DrawCommand.PushFractionalClip(FloatRect(edge, edge, viewport.width - edge, viewport.height - edge))
            }
        }

    // Scalar extrema across the complete stack form an independent oracle; it does not replay prefix updates.
    private fun completeClip(
        clips: List<DrawCommand>,
        viewport: IntSize,
    ): IntRect {
        val rectangles =
            listOf(IntRect(0, 0, viewport.width, viewport.height)) +
                clips.map { command ->
                    when (command) {
                        is DrawCommand.PushClip -> {
                            command.bounds
                        }

                        is DrawCommand.PushFractionalClip -> {
                            IntRect(
                                floor(command.bounds.left.coerceIn(0f, viewport.width.toFloat())).toInt(),
                                floor(command.bounds.top.coerceIn(0f, viewport.height.toFloat())).toInt(),
                                ceil(command.bounds.right.coerceIn(0f, viewport.width.toFloat())).toInt(),
                                ceil(command.bounds.bottom.coerceIn(0f, viewport.height.toFloat())).toInt(),
                            )
                        }

                        else -> {
                            error("The clip oracle accepts only push commands.")
                        }
                    }
                }
        val left = rectangles.maxOf { it.left }
        val top = rectangles.maxOf { it.top }
        return IntRect(left, top, maxOf(left, rectangles.minOf { it.right }), maxOf(top, rectangles.minOf { it.bottom }))
    }

    private fun mixedCommands(
        clips: List<DrawCommand>,
        viewport: IntSize,
    ): List<DrawCommand> {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { 0xFF112200.toInt() or it })
        return clips +
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, viewport.width, viewport.height), ArgbColor(0xFF102030.toInt())),
                DrawCommand.BlitImage(image, IntRect(0, 0, 4, 4), IntRect(3, 1, 7, 5)),
                DrawCommand.BlitImagePixels(image, IntRect(0, 0, 4, 4), IntRect(8, 1, 12, 5)),
                DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), FloatRect(4f, 5f, 8f, 9f), alphaCutoff = 0f),
                DrawCommand.FillRectangle(IntRect(10, 5, 14, 9), ArgbColor(0xFF445566.toInt())),
                DrawCommand.Platform(TestPlatform(ArgbColor(0xFF778899.toInt())), IntRect(3, 6, 4, 8)),
                DrawCommand.FillRectangle(IntRect(11, 3, 12, 4), ArgbColor(0xFFAABBCC.toInt())),
            ) + List(clips.size) { DrawCommand.PopClip }
    }

    private fun portablePlatform(command: DrawCommand): DrawCommand = if (command is DrawCommand.Platform) DrawCommand.FillRectangle(command.bounds, (command.command as TestPlatform).color) else command

    private fun reconstruct(layers: List<FabricMinecraftFrameLayer>): List<DrawCommand> =
        buildList {
            layers.forEach { layer ->
                when (layer) {
                    is FabricMinecraftFrameLayer.Portable -> {
                        add(DrawCommand.PushClip(layer.bounds))
                        addAll(globalCommands(layer))
                        add(DrawCommand.PopClip)
                    }

                    is FabricMinecraftFrameLayer.Sampled -> {
                        layer.clip?.let { add(DrawCommand.PushClip(it)) }
                        add(layer.command)
                        if (layer.clip != null) add(DrawCommand.PopClip)
                    }

                    is FabricMinecraftFrameLayer.Platform -> {
                        layer.clip?.let { add(DrawCommand.PushClip(it)) }
                        add(portablePlatform(layer.command))
                        if (layer.clip != null) add(DrawCommand.PopClip)
                    }
                }
            }
        }

    private fun globalCommands(layer: FabricMinecraftFrameLayer.Portable): List<DrawCommand> {
        if (layer.absoluteCoordinates) return layer.commands
        val offset = IntOffset(layer.bounds.left, layer.bounds.top)
        return layer.commands.map { command ->
            when (command) {
                is DrawCommand.FillRectangle -> command.copy(bounds = command.bounds + offset)
                is DrawCommand.BlitImage -> command.copy(destination = command.destination + offset)
                is DrawCommand.BlitImagePixels -> command.copy(destination = command.destination + offset)
                is DrawCommand.PushClip -> command.copy(bounds = command.bounds + offset)
                is DrawCommand.PushFractionalClip -> command.copy(bounds = FloatRect(command.bounds.left + offset.x, command.bounds.top + offset.y, command.bounds.right + offset.x, command.bounds.bottom + offset.y))
                DrawCommand.PopClip -> command
                is DrawCommand.SampledImage, is DrawCommand.Platform -> error("Localized ordinary runs cannot contain native barriers or sampled commands.")
            }
        }
    }

    private enum class ClipPattern {
        Integer,
        Fractional,
        Mixed,
    }

    private data class TestPlatform(
        val color: ArgbColor,
    ) : PlatformDrawCommand
}
