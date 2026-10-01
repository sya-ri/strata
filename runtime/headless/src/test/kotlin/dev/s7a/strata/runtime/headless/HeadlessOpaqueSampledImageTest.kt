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
 * Verifies opaque sampled colors against independent pixel-center coverage and integer tint arithmetic.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessOpaqueSampledImageTest {
    @Test
    // Keep the finite density, identity/non-identity tint, and cutoff matrix together.
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    fun opaqueSamplesPreserveFractionalCoverageAndTint() {
        val viewport = IntSize(8, 6)
        val colors = IntArray(12) { if (it == 6) -1 else 0xFF000000.toInt() or (it * 17 shl 16) or (it * 11 shl 8) or (it * 7) }
        val image = createDrawImage(IntSize(4, 3), colors)
        val destination = FloatRect(0.25f, 0.75f, 7.75f, 5.25f)
        val clip = FloatRect(0.3f, 1.2f, 5.65f, 5.35f)
        val background = 0x80908070.toInt()
        for (scale in 1..4) {
            for (tint in listOf(-1, 0xFF80FF40.toInt())) {
                for (cutoff in listOf(0.1f, 1f)) {
                    val commands =
                        listOf(
                            DrawCommand.FillRectangle(IntRect(0, 0, 8, 6), ArgbColor(background)),
                            DrawCommand.PushClip(IntRect(1, 0, 7, 6)),
                            DrawCommand.PushFractionalClip(clip),
                            DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 3f), destination, ArgbColor(tint), alphaCutoff = cutoff),
                            DrawCommand.PopClip,
                            DrawCommand.PopClip,
                        )
                    val expected =
                        IntArray(viewport.width * viewport.height * scale * scale) { index ->
                            val x = (index % (viewport.width * scale) + 0.5) / scale
                            val y = (index / (viewport.width * scale) + 0.5) / scale
                            val covered =
                                1 <= x && x < 7 && destination.left <= x && x < destination.right && destination.top <= y && y < destination.bottom &&
                                    clip.left <= x && x < clip.right && clip.top <= y && y < clip.bottom
                            if (covered) {
                                val sourceX = floor((x - 0.25) / 7.5 * 4).toInt().coerceIn(0, 3)
                                val sourceY = floor((y - 0.75) / 4.5 * 3).toInt().coerceIn(0, 2)
                                val source = colors[sourceY * 4 + sourceX]
                                val red = ((source ushr 16 and 255) * (tint ushr 16 and 255) + 127) / 255
                                val green = ((source ushr 8 and 255) * (tint ushr 8 and 255) + 127) / 255
                                val blue = ((source and 255) * (tint and 255) + 127) / 255
                                0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                            } else {
                                background
                            }
                        }
                    assertArrayEquals(expected, rasterizeHeadless(commands, viewport, scale).copyArgb(), "$scale/$tint/$cutoff")
                }
            }
        }
    }
}
