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
