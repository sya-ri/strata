package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import kotlin.math.floor

/**
 * Physical coverage and straight-alpha rounding remain exact for uniform and mixed destination colors.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessTranslucentFillTest {
    @Test
    internal fun initialFullImageOwnsOutputAndCanonicalizesTransparentPixels() {
        val size = IntSize(16, 16)
        val full = IntRect(0, 0, size.width, size.height)
        val original = IntArray(256) { (it shl 24) or ((it * 73_471) and 0xFFFFFF) }
        val image = createDrawImage(size, original)
        val commands = listOf(DrawCommand.BlitImage(image, full, full))
        val normalized = original.map { over(it, 0) }
        for (scale in 1..4) {
            val expected = IntArray(256 * scale * scale) { index -> normalized[(index / (size.width * scale) / scale) * size.width + index % (size.width * scale) / scale] }
            val rendered = rasterizeHeadless(commands, size, scale)
            assertArrayEquals(expected, rendered.copyArgb())
            rendered.copyArgb().fill(-1)
            assertArrayEquals(expected, rendered.copyArgb())
            assertArrayEquals(original, image.copyArgb())
            val foreground = 0x80123456.toInt()
            assertArrayEquals(expected.map { over(foreground, it) }.toIntArray(), rasterizeHeadless(commands + DrawCommand.FillRectangle(full, ArgbColor(foreground)), size, scale).copyArgb())
        }
    }

    @Test
    internal fun oneLargeFullAreaFillMatchesEveryAlphaAndChannelStateAtMultipleDensities() {
        for (scale in listOf(1, 2, 4)) {
            val side = 1024 / scale
            val size = IntSize(side, side)
            val full = IntRect(0, 0, side, side)
            val original = IntArray(side * side) { value -> ((value ushr 8 and 255) shl 24) or ((value and 255) shl 16) or ((value * 7 and 255) shl 8) or (value * 13 and 255) }
            val image = createDrawImage(size, original)
            for (foreground in listOf(0x00123456, 0x01010203, 0x80234567.toInt(), 0xFE334455.toInt(), -1)) {
                val commands = listOf(DrawCommand.BlitImage(image, full, full), DrawCommand.FillRectangle(full, ArgbColor(foreground)))
                val logical = original.map { over(foreground, over(it, 0)) }
                val expected = IntArray(1024 * 1024) { index -> logical[(index / 1024 / scale) * side + index % 1024 / scale] }
                assertArrayEquals(expected, rasterizeHeadless(commands, size, scale).copyArgb(), "$scale/$foreground")
                assertArrayEquals(original, image.copyArgb())
            }
        }
    }

    @Test
    internal fun deferredUniformPixelsMaterializeBeforeImagesAndPartialFills() {
        val viewport = IntSize(4, 3)
        val full = IntRect(-1, -1, 5, 4)
        val foreground = 0x10FFFFFF
        val imageColor = 0x80FF0011.toInt()
        val image = createDrawImage(IntSize(1, 1), intArrayOf(imageColor))
        val commands =
            buildList {
                add(DrawCommand.FillRectangle(full, ArgbColor(0x80314259.toInt())))
                add(DrawCommand.PushClip(IntRect(-2, -2, 6, 5)))
                repeat(64) { add(DrawCommand.FillRectangle(full, ArgbColor(foreground))) }
                add(DrawCommand.PopClip)
                add(DrawCommand.BlitImage(image, IntRect(0, 0, 1, 1), IntRect(0, 0, 2, 3)))
                add(DrawCommand.FillRectangle(IntRect(1, 0, 3, 3), ArgbColor(0x80334455.toInt())))
                add(DrawCommand.FillRectangle(full, ArgbColor(0x80112233.toInt())))
            }
        var uniform = over(0x80314259.toInt(), 0)
        repeat(64) { uniform = over(foreground, uniform) }
        for (scale in 1..4) {
            val expected =
                IntArray(viewport.width * viewport.height * scale * scale) { index ->
                    val x = index % (viewport.width * scale) / scale
                    var color = if (x < 2) over(imageColor, uniform) else uniform
                    if (1 <= x && x < 3) color = over(0x80334455.toInt(), color)
                    over(0x80112233.toInt(), color)
                }
            assertArrayEquals(expected, rasterizeHeadless(commands, viewport, scale).copyArgb())
            val replacement = commands + DrawCommand.FillRectangle(full, ArgbColor(-1)) + DrawCommand.FillRectangle(full, ArgbColor(0x80224466.toInt()))
            assertArrayEquals(IntArray(expected.size) { over(0x80224466.toInt(), -1) }, rasterizeHeadless(replacement, viewport, scale).copyArgb())
        }
    }

    @Test
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod") // A bounded matrix covers interacting physical clip and alpha cases.
    internal fun orderedTranslucentFillsMatchAnIndependentPixelOracle() {
        val viewport = IntSize(8, 6)
        val palette = listOf(0x00123456, 0x01010203, 0x80314259.toInt(), 0xFFABCDEF.toInt())
        val sources = listOf(0x00112233, 0x01123456, 0x10FFFFFF, 0x80314259.toInt(), 0xFE123456.toInt())
        val clip = FloatRect(0.3f, 1.2f, 5.65f, 5.35f)
        val rectangles = listOf(IntRect(-3, -2, 9, 7), IntRect(2, 1, 5, 4), IntRect(20, 20, 23, 22), IntRect(0, 0, 0, 6))
        for (scale in 1..4) {
            for (uniform in listOf(false, true)) {
                for (bounds in rectangles) {
                    for (source in sources) {
                        val commands =
                            buildList {
                                repeat(viewport.height) { y ->
                                    repeat(viewport.width) { x ->
                                        val color = palette[if (uniform) 2 else (x + y) % palette.size]
                                        add(DrawCommand.FillRectangle(IntRect(x, y, x + 1, y + 1), ArgbColor(color)))
                                    }
                                }
                                add(DrawCommand.PushClip(IntRect(1, 0, 7, 6)))
                                add(DrawCommand.PushFractionalClip(clip))
                                repeat(64) { add(DrawCommand.FillRectangle(bounds, ArgbColor(source))) }
                                add(DrawCommand.PopClip)
                                add(DrawCommand.PopClip)
                            }
                        val expected =
                            IntArray(viewport.width * viewport.height * scale * scale) { index ->
                                val x = (index % (viewport.width * scale) + 0.5) / scale
                                val y = (index / (viewport.width * scale) + 0.5) / scale
                                var color = over(palette[if (uniform) 2 else (x.toInt() + y.toInt()) % palette.size], 0)
                                val withinX = bounds.left <= x && x < bounds.right
                                val withinY = bounds.top <= y && y < bounds.bottom
                                val integerClipX = 1 <= x && x < 7
                                val fractionalClipX = clip.left <= x && x < clip.right
                                val fractionalClipY = clip.top <= y && y < clip.bottom
                                val covered = withinX && withinY && integerClipX
                                if (covered && fractionalClipX && fractionalClipY) repeat(64) { color = over(source, color) }
                                color
                            }
                        assertArrayEquals(expected, rasterizeHeadless(commands, viewport, scale).copyArgb(), "$scale/$uniform/$bounds/$source")
                    }
                }
            }
        }
    }

    @Test
    internal fun largeMixedPixelRunsPreserveEveryAlphaAndChannelAndStopAtBarriers() {
        val size = IntSize(256, 256)
        val full = IntRect(0, 0, 256, 256)
        val original =
            IntArray(65_536) { value ->
                val x = value and 255
                val y = value ushr 8
                (y shl 24) or (x shl 16) or (((x * 7 + y * 3) and 255) shl 8) or ((x * 13 + y) and 255)
            }
        val image = createDrawImage(size, original)
        val sources =
            List(64) { index ->
                (listOf(0, 1, 16, 128, 254)[index % 5] shl 24) or ((index * 73_471) and 0xFFFFFF)
            }
        val commands = listOf(DrawCommand.BlitImage(image, full, full)) + sources.map { DrawCommand.FillRectangle(full, ArgbColor(it)) }
        val logical = original.map { pixel -> sources.fold(over(pixel, 0)) { color, source -> over(source, color) } }
        val expected = IntArray(512 * 512) { index -> logical[(index / 512 / 2) * 256 + index % 512 / 2] }
        assertArrayEquals(expected, rasterizeHeadless(commands, size, 2).copyArgb())
        val interrupted =
            commands +
                listOf(
                    DrawCommand.PushFractionalClip(FloatRect(0.1f, 0.1f, 255.9f, 255.9f)),
                    DrawCommand.FillRectangle(full, ArgbColor(0x10224466)),
                    DrawCommand.PopClip,
                    DrawCommand.BlitImage(image, full, full),
                ) + sources.take(2).map { DrawCommand.FillRectangle(full, ArgbColor(it)) }
        val baseline = rasterizeHeadless(interrupted, size, 1).copyArgb()
        val repeated = IntArray(512 * 512) { index -> baseline[(index / 512 / 2) * 256 + index % 512 / 2] }
        // At scale two, the fractional clip covers all physical centers, as it does at scale one.
        assertArrayEquals(repeated, rasterizeHeadless(interrupted, size, 2).copyArgb())
        assertArrayEquals(expected, rasterizeHeadless(commands, size, 2).copyArgb())
        val lowAlpha = List(16) { 0x01000000 or ((it * 734_719) and 0xFFFFFF) }
        val lowCommands = listOf(DrawCommand.BlitImage(image, full, full)) + lowAlpha.map { DrawCommand.FillRectangle(full, ArgbColor(it)) }
        val lowLogical = original.map { pixel -> lowAlpha.fold(over(pixel, 0)) { color, source -> over(source, color) } }
        val lowExpected = IntArray(512 * 512) { index -> lowLogical[(index / 512 / 2) * 256 + index % 512 / 2] }
        assertArrayEquals(lowExpected, rasterizeHeadless(lowCommands, size, 2).copyArgb())
    }

    @Test
    internal fun matchingBlitsPreserveSourceOffsetsAndFractionalClipsAtEveryDensity() {
        val size = IntSize(5, 4)
        val sourceSize = IntSize(6, 5)
        val palette = listOf(0x00123456, 0x01FFFFFF, 0x80FFFFFF.toInt(), 0xFEFFFFFF.toInt(), -1, 0xFF123456.toInt())
        val sourcePixels = IntArray(30) { palette[it % palette.size] }
        val image = createDrawImage(sourceSize, sourcePixels)
        val source = IntRect(1, 1, 5, 4)
        val destination = IntRect(-1, -1, 3, 2)
        val clip = FloatRect(0.25f, 0.1f, 2.65f, 1.6f)
        for (scale in 1..4) {
            for (base in listOf(0x00123456, 0x80102030.toInt(), -1)) {
                val commands =
                    listOf(
                        DrawCommand.FillRectangle(IntRect(0, 0, size.width, size.height), ArgbColor(base)),
                        DrawCommand.PushClip(IntRect(0, 0, 3, 2)),
                        DrawCommand.PushFractionalClip(clip),
                        DrawCommand.BlitImage(image, source, destination),
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                    )
                val expected =
                    IntArray(size.width * size.height * scale * scale) { index ->
                        val x = (index % (size.width * scale) + 0.5) / scale
                        val y = (index / (size.width * scale) + 0.5) / scale
                        val horizontal = clip.left <= x && x < clip.right
                        val vertical = clip.top <= y && y < clip.bottom
                        val background = over(base, 0)
                        if (horizontal && vertical) {
                            val sourceX = source.left + x.toInt() - destination.left
                            val sourceY = source.top + y.toInt() - destination.top
                            over(sourcePixels[sourceY * sourceSize.width + sourceX], background)
                        } else {
                            background
                        }
                    }
                assertArrayEquals(expected, rasterizeHeadless(commands, size, scale).copyArgb(), "$scale/$base")
            }
        }
    }

    @Test
    internal fun commandPaletteCollisionsPreserveOrderedBlendsAndClippedCoverage() {
        val size = IntSize(128, 64)
        val full = IntRect(0, 0, size.width, size.height)
        val original = IntArray(size.width * size.height) { value -> (value * 734_719) xor (value shl 24) }
        val image = createDrawImage(size, original)
        val sources = List(16) { (listOf(1, 16, 128, 254)[it % 4] shl 24) or ((it * 73_471) and 0xFFFFFF) }
        val clip = FloatRect(0.25f, 0.1f, 127.65f, 63.8f)
        val commands =
            listOf(DrawCommand.BlitImage(image, full, full), DrawCommand.PushFractionalClip(clip)) +
                sources.map { DrawCommand.FillRectangle(full, ArgbColor(it)) } + DrawCommand.PopClip
        for (scale in 1..4) {
            val expected =
                IntArray(size.width * size.height * scale * scale) { index ->
                    val x = (index % (size.width * scale) + 0.5) / scale
                    val y = (index / (size.width * scale) + 0.5) / scale
                    val background = over(original[y.toInt() * size.width + x.toInt()], 0)
                    val horizontal = clip.left <= x && x < clip.right
                    val vertical = clip.top <= y && y < clip.bottom
                    if (horizontal && vertical) {
                        sources.fold(background) { color, source -> over(source, color) }
                    } else {
                        background
                    }
                }
            assertArrayEquals(expected, rasterizeHeadless(commands, size, scale).copyArgb(), "$scale")
        }
    }

    /**
     * Independent floating-point reference for the documented half-up straight-alpha equations.
     */
    private fun over(
        source: Int,
        destination: Int,
    ): Int {
        val alpha = (source ushr 24).toDouble()
        val destinationAlpha = (destination ushr 24).toDouble()
        val denominator = alpha * 255 + destinationAlpha * (255 - alpha)
        if (denominator == 0.0) return 0
        var result = floor(denominator / 255 + 0.5).toInt() shl 24
        for (shift in listOf(16, 8, 0)) {
            val numerator = (source ushr shift and 255) * alpha * 255 + (destination ushr shift and 255) * destinationAlpha * (255 - alpha)
            result = result or (floor(numerator / denominator + 0.5).toInt() shl shift)
        }
        return result
    }
}
