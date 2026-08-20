package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * Verifies nearest pixel-center image sampling, clipping, blending, ordering, and physical replication.
 */
internal class HeadlessBlitTest {
    @Test
    fun upscalingDownscalingAndNonIntegerRatiosUsePixelCenters() {
        val source2x2 =
            image(
                IntSize(2, 2),
                intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()),
            )
        val upscaled =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source2x2, IntRect(0, 0, 2, 2), IntRect(0, 0, 4, 4))),
                IntSize(4, 4),
            )
        assertArrayEquals(
            intArrayOf(
                0xFFFF0000.toInt(),
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF00FF00.toInt(),
                0xFFFF0000.toInt(),
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF0000FF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFFFFFFFF.toInt(),
            ),
            upscaled.copyArgb(),
        )

        val source4x1 =
            image(
                IntSize(4, 1),
                intArrayOf(0xFF010101.toInt(), 0xFF020202.toInt(), 0xFF030303.toInt(), 0xFF040404.toInt()),
            )
        val downscaled =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source4x1, IntRect(0, 0, 4, 1), IntRect(0, 0, 2, 1))),
                IntSize(2, 1),
            )
        assertArrayEquals(intArrayOf(0xFF020202.toInt(), 0xFF040404.toInt()), downscaled.copyArgb())

        val nonInteger =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source4x1, IntRect(0, 0, 3, 1), IntRect(0, 0, 2, 1))),
                IntSize(2, 1),
            )
        assertArrayEquals(intArrayOf(0xFF010101.toInt(), 0xFF030303.toInt()), nonInteger.copyArgb())
    }

    @Test
    fun clippingPreservesOriginalDestinationSamplingAndScaleReplicates() {
        val source =
            image(
                IntSize(4, 1),
                intArrayOf(0xFF101010.toInt(), 0xFF202020.toInt(), 0xFF303030.toInt(), 0xFF404040.toInt()),
            )
        val clipped =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(0, 0, 4, 1), IntRect(-1, 0, 3, 1))),
                IntSize(2, 1),
            )
        assertArrayEquals(intArrayOf(0xFF202020.toInt(), 0xFF303030.toInt()), clipped.copyArgb())

        val rightClipped =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(0, 0, 4, 1), IntRect(1, 0, 5, 1))),
                IntSize(2, 1),
            )
        assertArrayEquals(intArrayOf(0, 0xFF101010.toInt()), rightClipped.copyArgb())

        val verticalSource =
            image(
                IntSize(1, 4),
                intArrayOf(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt(), 0xFF444444.toInt()),
            )
        val topClipped =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(verticalSource, IntRect(0, 0, 1, 4), IntRect(0, -1, 1, 3))),
                IntSize(1, 2),
            )
        assertArrayEquals(intArrayOf(0xFF222222.toInt(), 0xFF333333.toInt()), topClipped.copyArgb())
        val bottomClipped =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(verticalSource, IntRect(0, 0, 1, 4), IntRect(0, 1, 1, 5))),
                IntSize(1, 2),
            )
        assertArrayEquals(intArrayOf(0, 0xFF111111.toInt()), bottomClipped.copyArgb())
        val scaled =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(1, 0, 3, 1), IntRect(0, 0, 2, 1))),
                IntSize(2, 1),
                scale = 2,
            )
        assertArrayEquals(
            intArrayOf(
                0xFF202020.toInt(),
                0xFF202020.toInt(),
                0xFF303030.toInt(),
                0xFF303030.toInt(),
                0xFF202020.toInt(),
                0xFF202020.toInt(),
                0xFF303030.toInt(),
                0xFF303030.toInt(),
            ),
            scaled.copyArgb(),
        )
    }

    @Test
    fun nonzeroSourceTopIsAddedAfterSampling() {
        val source =
            image(
                IntSize(1, 4),
                intArrayOf(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt(), 0xFF444444.toInt()),
            )
        val result =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(0, 1, 1, 3), IntRect(0, 0, 1, 2))),
                IntSize(1, 2),
            )

        assertArrayEquals(intArrayOf(0xFF222222.toInt(), 0xFF333333.toInt()), result.copyArgb())
    }

    @Test
    fun fullyOffscreenBlitIsAVisibleNoOp() {
        val source =
            image(
                IntSize(1, 1),
                intArrayOf(0xFF010101.toInt()),
            )
        val result =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(-4, 0, -1, 1))),
                IntSize(2, 1),
            )
        assertArrayEquals(intArrayOf(0, 0), result.copyArgb())
    }

    @Test
    fun commandOrderBlendAndImageReuseAreDeterministic() {
        val source = image(IntSize(1, 1), intArrayOf(0x8000FF00.toInt()))
        val blended =
            rasterizeHeadless(
                listOf(
                    DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(0xFFFF0000.toInt())),
                    DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1)),
                ),
                IntSize(1, 1),
            )
        assertEquals(0xFF7F8000.toInt(), blended.argbAt(0, 0))

        val reverse =
            rasterizeHeadless(
                listOf(
                    DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1)),
                    DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(0xFFFF0000.toInt())),
                ),
                IntSize(1, 1),
            )
        assertEquals(0xFFFF0000.toInt(), reverse.argbAt(0, 0))

        val reused =
            rasterizeHeadless(
                listOf(
                    DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1)),
                    DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(1, 0, 2, 1)),
                ),
                IntSize(2, 1),
            )
        assertArrayEquals(intArrayOf(0x8000FF00.toInt(), 0x8000FF00.toInt()), reused.copyArgb())
    }

    @Test
    fun maximumRepresentableDestinationExtentUsesCheckedSampling() {
        val source = image(IntSize(2, 1), intArrayOf(0xFF010101.toInt(), 0xFF020202.toInt()))
        val image =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(0, 0, 2, 1), IntRect(Int.MIN_VALUE + 2, 0, 1, 1))),
                IntSize(1, 1),
            )

        assertEquals(0xFF020202.toInt(), image.argbAt(0, 0))
    }

    @Test
    fun sharedImagesCanRenderConcurrently() {
        val source = image(IntSize(1, 1), intArrayOf(0x80123456.toInt()))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (0 until 4).map {
                    executor.submit {
                        repeat(20) {
                            val result =
                                rasterizeHeadless(
                                    listOf(DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1))),
                                    IntSize(1, 1),
                                )
                            assertEquals(0x80123456.toInt(), result.argbAt(0, 0))
                        }
                    }
                }
            futures.forEach { future -> future.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun image(
        size: IntSize,
        pixels: IntArray,
    ): DrawImage = createDrawImage(size, pixels)
}
