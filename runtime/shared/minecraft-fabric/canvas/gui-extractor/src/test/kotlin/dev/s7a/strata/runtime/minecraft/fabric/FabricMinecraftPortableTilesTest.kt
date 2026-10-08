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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Verifies exact disjoint fallback pixels, per-command accounting and bounded changed-region reuse.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPortableTilesTest {
    @Test
    fun largeRunsReadEachOccurrenceOnceAndPreservePerTileClipSelection() {
        val bounds = IntRect(-1024, -1024, 1024, 1024)
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x80ABCDEF.toInt(), 0x40123456, 0))
        val commands =
            buildList {
                add(DrawCommand.FillRectangle(bounds, ArgbColor(0x80456789.toInt())))
                add(DrawCommand.PushClip(IntRect(-900, -950, 900, 950)))
                add(DrawCommand.PushFractionalClip(FloatRect(-768.25f, -768.625f, 768.125f, 768.875f)))
                for (top in -1024 until 1024 step 256) {
                    for (left in -1024 until 1024 step 256) {
                        add(DrawCommand.PushClip(IntRect(left, top, left + 257, top + 257)))
                        add(DrawCommand.FillRectangle(IntRect(left + 255, top + 255, left + 257, top + 257), ArgbColor(-1)))
                        add(DrawCommand.SampledImage(image, FloatRect(0.125f, 0.25f, 1.875f, 1.75f), FloatRect(left + 255.25f, top + 255.75f, left + 257.125f, top + 258.625f), orientation = SampledImageOrientation.FlipHorizontal))
                        add(DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(left, top, left + 2, top + 2)))
                        add(DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(left + 2, top + 2, left + 4, top + 4)))
                        add(DrawCommand.PopClip)
                    }
                }
                add(DrawCommand.PushClip(IntRect(256, 256, 256, 257)))
                add(DrawCommand.FillRectangle(bounds, ArgbColor(-1)))
                add(DrawCommand.PopClip)
                add(DrawCommand.PushFractionalClip(FloatRect(2048.25f, 2048.25f, 2048.75f, 2048.75f)))
                add(DrawCommand.FillRectangle(bounds, ArgbColor(-1)))
                add(DrawCommand.PopClip)
                add(DrawCommand.PopClip)
                add(DrawCommand.PopClip)
                add(DrawCommand.FillRectangle(IntRect(-1025, -1025, -1024, -1024), ArgbColor(-1)))
                add(DrawCommand.FillRectangle(IntRect(1024, 1024, 1025, 1025), ArgbColor(-1)))
                add(DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(-768.5f, -768.5f, -767.5f, -767.5f)))
            }
        for (scale in 1..4) {
            var reads = 0
            val observed =
                object : AbstractList<DrawCommand>() {
                    override val size: Int get() = commands.size

                    override fun get(index: Int): DrawCommand {
                        reads++
                        return commands[index]
                    }
                }
            val actual = tileFabricMinecraftPortable(observed, bounds, scale, 7, 3, 2, 1)
            assertEquals(commands.size, reads)
            assertTrue(1 < actual.size && actual.size <= 64)
            if (scale == 1) assertEquals(64, actual.size)
            assertEquals(bounds.width.toLong() * bounds.height, actual.sumOf { it.bounds.width.toLong() * it.bounds.height })
            assertEquals(7, actual.sumOf { it.ineligibleSampledImages })
            assertEquals(3, actual.sumOf { it.capacitySampledImages })
            assertEquals(2, actual.sumOf { it.tintFallbackImages })
            assertEquals(1, actual.sumOf { it.alphaCutoffFallbackImages })
            for (tile in actual) assertEquals(referenceTileCommands(commands, tile.bounds), tile.commands, "GUI$scale ${tile.bounds}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tileFabricMinecraftPortable(listOf(DrawCommand.PopClip), bounds, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tileFabricMinecraftPortable(listOf(DrawCommand.PushClip(bounds)), bounds, 1)
        }
    }

    @Test
    fun disjointTilesShareBoundariesOnlyWithinTheirOriginalRun() {
        val bounds = IntRect(0, 0, 600, 450)
        val background = DrawCommand.FillRectangle(bounds, ArgbColor(0x80456789.toInt()))
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val sampled = FabricMinecraftFrameLayer.Sampled(DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(1f, 1f, 4f, 4f), alphaCutoff = 0f), null, IntRect(1, 1, 4, 4))
        val platform = FabricMinecraftFrameLayer.Platform(DrawCommand.Platform(object : PlatformDrawCommand {}, bounds), null)
        for (scale in 1..4) {
            val first = tileFabricMinecraftPortable(listOf(background), bounds, scale)
            val second = tileFabricMinecraftPortable(listOf(background), bounds, scale)
            assertTrue(1 < first.size)
            for (index in first.indices) {
                for (other in index + 1 until first.size) {
                    val left = first[index].bounds
                    val right = first[other].bounds
                    assertTrue(left.right <= right.left || right.right <= left.left || left.bottom <= right.top || right.bottom <= left.top)
                }
            }
            val boundary = Any()
            val events = ArrayList<Any>()
            val layers = first + listOf(sampled, platform) + second
            submitFabricMinecraftFrameLayers(layers, { events.add(boundary) }) { events.add(it) }
            assertEquals(first + listOf(boundary, sampled, boundary, platform, boundary) + second, events)
            events.clear()
            submitFabricMinecraftFrameLayers(first + second, { events.add(boundary) }) { events.add(it) }
            assertEquals(first + listOf(boundary) + second, events)
        }
    }

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

    // Preserve the independent per-tile interpreter as the command-order oracle for the one-pass distributor.
    private fun referenceTileCommands(
        commands: List<DrawCommand>,
        tile: IntRect,
    ): List<DrawCommand> {
        val selected = ArrayList<DrawCommand>()
        val clips = ArrayList<DrawCommand>()
        val coverage = arrayListOf(tile)
        var emittedDepth = 0
        for (command in commands) {
            when (command) {
                is DrawCommand.PushClip -> {
                    clips.add(command)
                    coverage.add(referenceIntersection(coverage.last(), command.bounds))
                }

                is DrawCommand.PushFractionalClip -> {
                    clips.add(command)
                    coverage.add(referenceEnclosure(command.bounds, coverage.last()))
                }

                DrawCommand.PopClip -> {
                    if (emittedDepth == clips.size) {
                        selected.add(command)
                        emittedDepth--
                    }
                    clips.removeAt(clips.lastIndex)
                    coverage.removeAt(coverage.lastIndex)
                }

                else -> {
                    val clip = coverage.last()
                    val visible =
                        when (command) {
                            is DrawCommand.FillRectangle -> referenceIntersection(command.bounds, clip)
                            is DrawCommand.BlitImage -> referenceIntersection(command.destination, clip)
                            is DrawCommand.BlitImagePixels -> referenceIntersection(command.destination, clip)
                            is DrawCommand.SampledImage -> referenceEnclosure(command.destination, clip)
                            else -> error("Unexpected nonportable command.")
                        }
                    if (0 < visible.width && 0 < visible.height) {
                        while (emittedDepth < clips.size) selected.add(clips[emittedDepth++])
                        selected.add(command)
                    }
                }
            }
        }
        return selected
    }

    private fun referenceIntersection(
        first: IntRect,
        second: IntRect,
    ): IntRect {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        return IntRect(left, top, maxOf(left, minOf(first.right, second.right)), maxOf(top, minOf(first.bottom, second.bottom)))
    }

    private fun referenceEnclosure(
        bounds: FloatRect,
        clip: IntRect,
    ): IntRect {
        val left = floor(bounds.left.toDouble().coerceIn(clip.left.toDouble(), clip.right.toDouble())).toInt()
        val top = floor(bounds.top.toDouble().coerceIn(clip.top.toDouble(), clip.bottom.toDouble())).toInt()
        val right = ceil(bounds.right.toDouble().coerceIn(left.toDouble(), clip.right.toDouble())).toInt()
        val bottom = ceil(bounds.bottom.toDouble().coerceIn(top.toDouble(), clip.bottom.toDouble())).toInt()
        return IntRect(left, top, right, bottom)
    }

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
