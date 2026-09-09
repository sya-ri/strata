package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies clip coverage independently of source sampling and backend parity.
 */
internal class HeadlessFractionalClipTest {
    @Test
    fun nestedClipsMaskFinalPixelCentersWithoutChangingAnyPrimitiveSampling() {
        val viewport = IntSize(6, 6)
        val image = createDrawImage(IntSize(3, 3), IntArray(9) { 0x80112233.toInt() + it * 0x110503 })
        val source = IntRect(0, 0, 3, 3)
        val destination = IntRect(-2, -1, 7, 6)
        val primitives =
            listOf(
                DrawCommand.FillRectangle(destination, ArgbColor(0x80abcdef.toInt())),
                DrawCommand.BlitImage(image, source, destination),
                DrawCommand.BlitImagePixels(image, source, destination),
                DrawCommand.SampledImage(
                    image,
                    FloatRect(0f, 0f, 3f, 3f),
                    FloatRect(-1.25f, -0.75f, 6.5f, 7.25f),
                    ArgbColor(0xaaddbb99.toInt()),
                    0.1f,
                    SampledImageOrientation.FlipBoth,
                ),
            )
        val background = DrawCommand.FillRectangle(IntRect(0, 0, 6, 6), ArgbColor(0xff314159.toInt()))
        for (scale in 1..4) {
            for (primitive in primitives) {
                val reference = rasterizeHeadless(listOf(background, primitive), viewport, scale).copyArgb()
                val expected = reference.copyOf()
                val width = viewport.width * scale
                for (index in expected.indices) {
                    val x = (index % width + 0.5) / scale
                    val y = (index / width + 0.5) / scale
                    val horizontal = 0.75 <= x && x < 4.0
                    val vertical = 1.25 <= y && y < 3.75
                    if ((horizontal && vertical).not()) expected[index] = background.color.value
                }
                val commands =
                    listOf(
                        background,
                        DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.5f, 4.5f, 3.75f)),
                        DrawCommand.PushClip(IntRect(0, 0, 4, 4)),
                        DrawCommand.PushFractionalClip(FloatRect(0.75f, 1.25f, 8f, 4f)),
                        primitive,
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                    )
                assertArrayEquals(expected, rasterizeHeadless(commands, viewport, scale).copyArgb(), "scale=$scale primitive=$primitive")
            }
        }
    }

    @Test
    fun emptyAndOffscreenClipsRemainEmptyWithoutOverflow() {
        val viewport = IntSize(3, 3)
        val fill = DrawCommand.FillRectangle(IntRect(0, 0, 3, 3), ArgbColor(-1))
        val clips =
            listOf(
                DrawCommand.PushFractionalClip(FloatRect(0.75f, 0.75f, 0.75f, 2.5f)),
                DrawCommand.PushFractionalClip(FloatRect(-1e20f, -1e20f, -1f, -1f)),
                DrawCommand.PushFractionalClip(FloatRect(1e20f, 1e20f, 1e21f, 1e21f)),
                DrawCommand.PushClip(IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1)),
            )
        for (clip in clips) {
            assertArrayEquals(IntArray(144), rasterizeHeadless(listOf(clip, fill, DrawCommand.PopClip), viewport, 4).copyArgb())
        }
        assertThrows(IllegalArgumentException::class.java) {
            rasterizeHeadless(listOf(DrawCommand.PushFractionalClip(FloatRect(0f, 0f, 1f, 1f))), viewport)
        }
    }
}
