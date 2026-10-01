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

/**
 * Verifies that both single-texel image commands retain fill coverage and source-over semantics.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessSingleTexelBlitTest {
    @Test
    // The finite matrix covers both command representations, alpha values, density, and clipped extents.
    @Suppress("NestedBlockDepth")
    fun constantImageSourcesMatchFillsAcrossClipsAndAlpha() {
        val viewport = IntSize(8, 6)
        val source = IntRect(1, 1, 2, 2)
        for (scale in 1..4) {
            for (alpha in listOf(0, 96, 192, 255)) {
                val color = alpha shl 24 or 0xABCDEF
                val image = createDrawImage(IntSize(3, 3), IntArray(9) { if (it == 4) color else 0xFF000000.toInt() })
                for (bounds in listOf(IntRect(-3, -2, 9, 7), IntRect(2, 1, 5, 4), IntRect(20, 20, 23, 22), IntRect(1, 1, 2, 2))) {
                    val prefix =
                        listOf(
                            DrawCommand.FillRectangle(IntRect(0, 0, 8, 6), ArgbColor(0x80123456.toInt())),
                            DrawCommand.FillRectangle(IntRect(2, 2, 7, 6), ArgbColor(0xFF654321.toInt())),
                            DrawCommand.PushClip(IntRect(1, 0, 7, 6)),
                            DrawCommand.PushFractionalClip(FloatRect(0.3f, 1.2f, 5.65f, 5.35f)),
                        )
                    val suffix = listOf(DrawCommand.PopClip, DrawCommand.PopClip)
                    val expected = rasterizeHeadless(prefix + DrawCommand.FillRectangle(bounds, ArgbColor(color)) + suffix, viewport, scale).copyArgb()
                    for (command in listOf(DrawCommand.BlitImage(image, source, bounds), DrawCommand.BlitImagePixels(image, source, bounds))) {
                        assertArrayEquals(expected, rasterizeHeadless(prefix + command + suffix, viewport, scale).copyArgb(), "$scale/$alpha/$bounds/$command")
                    }
                }
            }
        }
    }
}
