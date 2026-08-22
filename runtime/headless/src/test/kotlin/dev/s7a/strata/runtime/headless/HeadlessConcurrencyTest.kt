@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * Verifies independent concurrent low-level calls do not share mutable output state.
 */
internal class HeadlessConcurrencyTest {
    @Test
    fun concurrentRasterCallsProduceIndependentImages() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (0 until 8).map {
                    executor.submit<IntArray> {
                        rasterizeHeadless(
                            listOf(
                                DrawCommandFactory.fill(
                                    IntRect(0, 0, 2, 1),
                                    0xFF123456.toInt(),
                                ),
                            ),
                            IntSize(2, 1),
                        ).copyArgb()
                    }
                }
            futures.forEach { future ->
                assertArrayEquals(intArrayOf(0xFF123456.toInt(), 0xFF123456.toInt()), future.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oneReturnedImageSupportsConcurrentReadCopyAndPngCalls() {
        val image =
            rasterizeHeadless(
                listOf(DrawCommandFactory.fill(IntRect(0, 0, 1, 1), 0x80402010.toInt())),
                IntSize(1, 1),
            )
        val expectedPng = image.encodePng()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (0 until 8).map {
                    executor.submit<Triple<Int, IntArray, ByteArray>> {
                        val pixel = image.argbAt(0, 0)
                        val copy = image.copyArgb()
                        val png = image.encodePng()
                        Triple(pixel, copy, png)
                    }
                }
            futures.forEach { future ->
                val result = future.get()
                assertEquals(0x80402010.toInt(), result.first)
                assertArrayEquals(intArrayOf(0x80402010.toInt()), result.second)
                assertTrue(result.third !== expectedPng)
                assertArrayEquals(expectedPng, result.third)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oneReturnedFrameSupportsConcurrentExactPropertyReads() {
        val probe = HeadlessProbe()
        val description =
            evaluateComponentTree {
                Row {
                    element(HeadlessPrimitive(probe, color = ArgbColor(0xFF123456.toInt())))
                }
            }
        val frame = renderHeadless(description, IntSize(2, 2), scale = 2)
        val expectedSemantics = frame.semantics
        val expectedImage = frame.image
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                (0 until 8).map {
                    executor.submit<FrameRead> {
                        FrameRead(frame.viewport, frame.pixelScale, frame.image, frame.semantics)
                    }
                }
            futures.forEach { future ->
                val result = future.get()
                assertEquals(IntSize(2, 2), result.viewport)
                assertEquals(2, result.pixelScale)
                assertSame(expectedImage, result.image)
                assertEquals(expectedSemantics, result.semantics)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private object DrawCommandFactory {
        fun fill(
            bounds: IntRect,
            color: Int,
        ): DrawCommand.FillRectangle = DrawCommand.FillRectangle(bounds, ArgbColor(color))
    }

    private data class FrameRead(
        val viewport: IntSize,
        val pixelScale: Int,
        val image: HeadlessImage,
        val semantics: List<SemanticsEntry>,
    )
}
