package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
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
    fun fractionalSamplingUsesFinalPixelCentersAtEveryDensity() {
        val source = image(IntSize(4, 2), (1..8).map { value -> 0xFF000000.toInt() or value }.toIntArray())
        val command = DrawCommand.SampledImage(source, FloatRect(0f, 0f, 4f, 2f), FloatRect(0.25f, 0.25f, 1.25f, 1.25f))
        val expected =
            listOf(
                arrayOf(intArrayOf(2, 0), intArrayOf(0, 0)),
                arrayOf(
                    intArrayOf(1, 3, 0, 0),
                    intArrayOf(5, 7, 0, 0),
                    intArrayOf(0, 0, 0, 0),
                    intArrayOf(0, 0, 0, 0),
                ),
                arrayOf(
                    intArrayOf(0, 0, 0, 0, 0, 0),
                    intArrayOf(0, 2, 3, 4, 0, 0),
                    intArrayOf(0, 6, 7, 8, 0, 0),
                    intArrayOf(0, 6, 7, 8, 0, 0),
                    intArrayOf(0, 0, 0, 0, 0, 0),
                    intArrayOf(0, 0, 0, 0, 0, 0),
                ),
            )
        for (scale in 1..3) {
            val result = rasterizeHeadless(listOf(command), IntSize(2, 2), scale)
            assertNumberedPixels(expected[scale - 1], result)
        }
    }

    @Test
    fun sourceOrientationReversesCoordinatesBeforeSelectingExactNearestTies() {
        val source = image(IntSize(4, 4), (1..16).map { value -> 0xFF000000.toInt() or value }.toIntArray())
        val expected =
            listOf(
                SampledImageOrientation.Normal to arrayOf(intArrayOf(1, 3, 4), intArrayOf(9, 11, 12), intArrayOf(13, 15, 16)),
                SampledImageOrientation.FlipHorizontal to arrayOf(intArrayOf(4, 3, 1), intArrayOf(12, 11, 9), intArrayOf(16, 15, 13)),
                SampledImageOrientation.FlipVertical to arrayOf(intArrayOf(13, 15, 16), intArrayOf(9, 11, 12), intArrayOf(1, 3, 4)),
                SampledImageOrientation.FlipBoth to arrayOf(intArrayOf(16, 15, 13), intArrayOf(12, 11, 9), intArrayOf(4, 3, 1)),
            )
        for ((orientation, pixels) in expected) {
            val command = DrawCommand.SampledImage(source, FloatRect(0f, 0f, 4f, 4f), FloatRect(0f, 0f, 3f, 3f), orientation = orientation)
            assertNumberedPixels(pixels, rasterizeHeadless(listOf(command), IntSize(3, 3)))
        }
    }

    @Test
    fun fractionalSourceEdgesAndHalfOpenDestinationEdgesArePreserved() {
        val source = image(IntSize(4, 1), (1..4).map { value -> 0xFF000000.toInt() or value }.toIntArray())
        val command = DrawCommand.SampledImage(source, FloatRect(0.75f, 0f, 2.75f, 1f), FloatRect(0.5f, 0.5f, 1.5f, 1.5f))
        assertNumberedPixels(
            arrayOf(intArrayOf(1, 0), intArrayOf(0, 0)),
            rasterizeHeadless(listOf(command), IntSize(2, 2)),
        )
        assertNumberedPixels(
            arrayOf(
                intArrayOf(0, 0, 0, 0),
                intArrayOf(0, 2, 3, 0),
                intArrayOf(0, 2, 3, 0),
                intArrayOf(0, 0, 0, 0),
            ),
            rasterizeHeadless(listOf(command), IntSize(2, 2), scale = 2),
        )
    }

    @Test
    fun nestedClipsPreserveFractionalMappingAtPhysicalDensity() {
        val source = image(IntSize(4, 1), (1..4).map { value -> 0xFF000000.toInt() or value }.toIntArray())
        val command = DrawCommand.SampledImage(source, FloatRect(0f, 0f, 4f, 1f), FloatRect(-0.5f, -0.25f, 3.5f, 0.75f))
        val commands =
            listOf(
                DrawCommand.PushClip(IntRect(1, 0, 3, 1)),
                DrawCommand.PushClip(IntRect(0, 0, 2, 1)),
                command,
                DrawCommand.PopClip,
                DrawCommand.PopClip,
            )
        assertNumberedPixels(
            arrayOf(intArrayOf(0, 0, 2, 3, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0)),
            rasterizeHeadless(commands, IntSize(3, 1), scale = 2),
        )
        assertNumberedPixels(
            arrayOf(intArrayOf(0, 0, 3, 2, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0)),
            rasterizeHeadless(
                commands.map { if (it is DrawCommand.SampledImage) it.copy(orientation = SampledImageOrientation.FlipBoth) else it },
                IntSize(3, 1),
                scale = 2,
            ),
        )
        assertNumberedPixels(
            arrayOf(intArrayOf(0, 0, 0, 0, 0, 0), intArrayOf(0, 0, 0, 0, 0, 0)),
            rasterizeHeadless(
                listOf(DrawCommand.PushClip(IntRect(1, 0, 1, 1)), command, DrawCommand.PopClip),
                IntSize(3, 1),
                scale = 2,
            ),
        )
    }

    @Test
    fun tintMultiplicationRemainsFractionalUntilTheFinalBlend() {
        val source = image(IntSize(1, 1), intArrayOf(0x339D5735))
        val bounds = FloatRect(0f, 0f, 1f, 1f)
        val result =
            rasterizeHeadless(
                listOf(
                    DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(0xFF285FAA.toInt())),
                    DrawCommand.SampledImage(source, bounds, bounds, ArgbColor(0x80C387E3.toInt())),
                ),
                IntSize(1, 1),
            )
        assertEquals(0xFF305A9E.toInt(), result.argbAt(0, 0))

        val intensity = image(IntSize(1, 1), intArrayOf(0x80808080.toInt()))
        val shaded =
            rasterizeHeadless(
                listOf(
                    DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(0xFF000000.toInt())),
                    DrawCommand.SampledImage(intensity, bounds, bounds, ArgbColor(0xFF123456.toInt())),
                ),
                IntSize(1, 1),
            )
        assertEquals(0xFF050D16.toInt(), shaded.argbAt(0, 0))
    }

    @Test
    fun alphaCutoffUsesTheMultipliedAlphaAndIncludesTheExactBoundary() {
        val bounds = FloatRect(0f, 0f, 1f, 1f)
        val source = image(IntSize(1, 1), intArrayOf(0x33FFFFFF))
        val tinted = DrawCommand.SampledImage(source, bounds, bounds, ArgbColor(0x80FFFFFF.toInt()))
        val multipliedAlpha = (51f / 255f) * (128f / 255f)
        assertEquals(0x1AFFFFFF, rasterizeHeadless(listOf(tinted), IntSize(1, 1)).argbAt(0, 0))
        assertEquals(
            0,
            rasterizeHeadless(listOf(tinted.copy(tint = ArgbColor(0x7FFFFFFF))), IntSize(1, 1)).argbAt(0, 0),
        )
        assertEquals(
            0x1AFFFFFF,
            rasterizeHeadless(listOf(tinted.copy(alphaCutoff = multipliedAlpha)), IntSize(1, 1)).argbAt(0, 0),
        )
        assertEquals(
            0,
            rasterizeHeadless(listOf(tinted.copy(alphaCutoff = Math.nextUp(multipliedAlpha))), IntSize(1, 1)).argbAt(0, 0),
        )
        val tiny = image(IntSize(1, 1), intArrayOf(0x01FFFFFF))
        assertEquals(
            0,
            rasterizeHeadless(
                listOf(DrawCommand.SampledImage(tiny, bounds, bounds, ArgbColor(0x01FFFFFF), alphaCutoff = 0f)),
                IntSize(1, 1),
            ).argbAt(0, 0),
        )
        val opaque = image(IntSize(1, 1), intArrayOf(-1))
        assertEquals(
            -1,
            rasterizeHeadless(listOf(DrawCommand.SampledImage(opaque, bounds, bounds, alphaCutoff = 1f)), IntSize(1, 1)).argbAt(0, 0),
        )
    }

    @Test
    fun laterFillAndBlitBlendEachPreviouslySampledPhysicalPixelIndependently() {
        val source = image(IntSize(2, 1), intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        val sampled = DrawCommand.SampledImage(source, FloatRect(0f, 0f, 2f, 1f), FloatRect(0f, 0f, 1f, 1f))
        val overlays =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(0x800000FF.toInt())),
                DrawCommand.BlitImage(image(IntSize(1, 1), intArrayOf(0x800000FF.toInt())), IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1)),
            )
        overlays.forEach { overlay ->
            assertArrayEquals(
                intArrayOf(0xFF7F0080.toInt(), 0xFF007F80.toInt(), 0xFF7F0080.toInt(), 0xFF007F80.toInt()),
                rasterizeHeadless(listOf(sampled, overlay), IntSize(1, 1), scale = 2).copyArgb(),
            )
            assertArrayEquals(
                intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
                rasterizeHeadless(listOf(overlay, sampled), IntSize(1, 1), scale = 2).copyArgb(),
            )
        }
    }

    @Test
    fun hugeOffscreenAndSubpixelDestinationsDoNotOverflowOrPaintOutsideTheViewport() {
        val source = image(IntSize(1, 1), intArrayOf(-1))
        val sourceBounds = FloatRect(0f, 0f, 1f, 1f)
        val destinations =
            listOf(
                FloatRect(Float.MAX_VALUE / 2f, 0f, Float.MAX_VALUE, 1f),
                FloatRect(-Float.MAX_VALUE, 0f, -Float.MAX_VALUE / 2f, 1f),
                FloatRect(0f, 0f, Float.MIN_VALUE, Float.MIN_VALUE),
            )
        destinations.forEach { destination ->
            assertArrayEquals(
                IntArray(9),
                rasterizeHeadless(listOf(DrawCommand.SampledImage(source, sourceBounds, destination)), IntSize(1, 1), scale = 3).copyArgb(),
            )
        }
        assertArrayEquals(
            IntArray(9) { -1 },
            rasterizeHeadless(
                listOf(DrawCommand.SampledImage(source, sourceBounds, FloatRect(-1_000_000f, -1_000_000f, 1_000_000f, 1_000_000f))),
                IntSize(1, 1),
                scale = 3,
            ).copyArgb(),
        )
    }

    @Test
    fun outputPixelImagesPreserveFourTexelsInsideOneScaledLogicalPixel() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
        val source = image(IntSize(2, 2), colors)
        val region = IntRect(0, 0, 2, 2)
        val destination = IntRect(0, 0, 1, 1)
        val pixels = rasterizeHeadless(listOf(DrawCommand.BlitImagePixels(source, region, destination)), IntSize(1, 1), scale = 2)
        assertArrayEquals(colors, pixels.copyArgb())

        val logical = rasterizeHeadless(listOf(DrawCommand.BlitImage(source, region, destination)), IntSize(1, 1), scale = 2)
        assertArrayEquals(IntArray(4) { 0xFFFFFF00.toInt() }, logical.copyArgb())
    }

    @Test
    fun ordinaryTranslucentCommandsBlendOverEveryDistinctOutputPixel() {
        val source = image(IntSize(2, 2), intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt()))
        val overlay = image(IntSize(1, 1), intArrayOf(0x80000000.toInt()))
        val bounds = IntRect(0, 0, 1, 1)
        val result =
            rasterizeHeadless(
                listOf(
                    DrawCommand.BlitImagePixels(source, IntRect(0, 0, 2, 2), bounds),
                    DrawCommand.FillRectangle(bounds, ArgbColor(0x80FFFFFF.toInt())),
                    DrawCommand.BlitImage(overlay, bounds, bounds),
                ),
                IntSize(1, 1),
                scale = 2,
            )
        assertArrayEquals(intArrayOf(0xFF7F4040.toInt(), 0xFF407F40.toInt(), 0xFF40407F.toInt(), 0xFF7F7F40.toInt()), result.copyArgb())
    }

    @Test
    fun outputPixelSamplingKeepsSourceOffsetsAndClippedDestinationMapping() {
        val source = image(IntSize(6, 2), IntArray(12) { index -> 0xFF000000.toInt() or index })
        val result =
            rasterizeHeadless(
                listOf(
                    DrawCommand.PushClip(IntRect(1, 0, 2, 1)),
                    DrawCommand.BlitImagePixels(source, IntRect(1, 0, 5, 2), IntRect(0, 0, 2, 1)),
                    DrawCommand.PopClip,
                ),
                IntSize(2, 1),
                scale = 2,
            )
        assertArrayEquals(
            intArrayOf(0, 0, 0xFF000003.toInt(), 0xFF000004.toInt(), 0, 0, 0xFF000009.toInt(), 0xFF00000A.toInt()),
            result.copyArgb(),
        )
        val largeDestination =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImagePixels(source, IntRect(1, 0, 5, 2), IntRect(Int.MIN_VALUE + 2, 0, 1, 1))),
                IntSize(1, 1),
                scale = 2,
            )
        assertArrayEquals(intArrayOf(0xFF000004.toInt(), 0xFF000004.toInt(), 0xFF00000A.toInt(), 0xFF00000A.toInt()), largeDestination.copyArgb())
    }

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

    private fun assertNumberedPixels(
        rows: Array<IntArray>,
        image: HeadlessImage,
    ) {
        val expected = rows.flatMap { row -> row.map { value -> if (value == 0) 0 else 0xFF000000.toInt() or value } }.toIntArray()
        assertEquals(IntSize(rows.first().size, rows.size), image.size)
        assertArrayEquals(expected, image.copyArgb())
    }
}
