package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * Verifies opaque row overwrites against independent physical pixel-center coverage.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessOpaqueFillTest {
    @Test
    // The finite matrix keeps density, alpha destination, empty extents, and both clip representations together.
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    fun opaqueFillsPreserveOrderedPixelsAcrossDensityAndNestedClips() {
        val viewport = IntSize(8, 6)
        val integerClip = IntRect(1, 0, 7, 6)
        val fractionalClip = FloatRect(0.3f, 1.2f, 5.65f, 5.35f)
        val rectangles = listOf(IntRect(-3, -2, 9, 7), IntRect(2, 1, 5, 4), IntRect(20, 20, 23, 22), IntRect(0, 0, 0, 6))
        for (scale in 1..4) {
            for (bounds in rectangles) {
                for (clipped in listOf(false, true)) {
                    for (color in listOf(0xFF000000.toInt(), 0xFFABCDEF.toInt(), 0xFFFFFFFF.toInt())) {
                        val commands =
                            buildList {
                                add(DrawCommand.FillRectangle(IntRect(0, 0, 8, 6), ArgbColor(0x80123456.toInt())))
                                if (clipped) {
                                    add(DrawCommand.PushClip(integerClip))
                                    add(DrawCommand.PushFractionalClip(fractionalClip))
                                }
                                add(DrawCommand.FillRectangle(bounds, ArgbColor(color)))
                                if (clipped) {
                                    add(DrawCommand.PopClip)
                                    add(DrawCommand.PopClip)
                                }
                            }
                        val expected =
                            IntArray(viewport.width * viewport.height * scale * scale) { index ->
                                val x = (index % (viewport.width * scale) + 0.5) / scale
                                val y = (index / (viewport.width * scale) + 0.5) / scale
                                val insideBounds = bounds.left <= x && x < bounds.right && bounds.top <= y && y < bounds.bottom
                                val insideClip =
                                    clipped.not() || (
                                        integerClip.left <= x && x < integerClip.right && integerClip.top <= y && y < integerClip.bottom &&
                                            fractionalClip.left <= x && x < fractionalClip.right && fractionalClip.top <= y && y < fractionalClip.bottom
                                    )
                                if (insideBounds && insideClip) color else 0x80123456.toInt()
                            }
                        assertArrayEquals(expected, rasterizeHeadless(commands, viewport, scale).copyArgb(), "$scale/$bounds/$clipped/$color")
                    }
                }
            }
        }
    }
}
