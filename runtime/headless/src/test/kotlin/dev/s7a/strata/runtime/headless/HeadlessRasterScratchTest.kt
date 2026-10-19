package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies exact unquantized products, LRU retention, terminal release and shared region parity.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("NestedBlockDepth") // Exhaustive arithmetic and rendering matrices keep their independent dimensions explicit.
internal class HeadlessRasterScratchTest {
    @Test
    fun productsPreserveEverySourceByteBeforeAndAfterTheAlphaRowLimit() {
        val scratch = HeadlessRasterScratch()
        scratch.use {
            for (tint in listOf(-1, 0xFFBFD7EF.toInt(), 0x80A4C6E8.toInt(), 0x01020406, 0x00010203, 0x7F8081FE)) {
                val weights = it.weights(tint)
                val tintAlpha = (tint ushr 24).toFloat() / 255f
                for (alpha in 0..255) {
                    val sourceAlpha = alpha.toFloat() / 255f * tintAlpha
                    assertEquals(sourceAlpha.toRawBits(), weights.alpha(alpha).toRawBits())
                    assertEquals((1f - sourceAlpha).toRawBits(), weights.inverse(alpha).toRawBits())
                    for (shift in listOf(16, 8, 0)) {
                        val channelTint = (tint ushr shift and 255).toFloat() / 255f
                        for (source in 0..255) {
                            val expected = source.toFloat() / 255f * channelTint * sourceAlpha
                            assertEquals(expected.toRawBits(), weights.channel(alpha, source, shift).toRawBits())
                        }
                    }
                }
                assertTrue(it.retainedBytes <= 256 * 1024)
            }
        }
        assertEquals(0, scratch.retainedBytes)
    }

    @Test
    fun accessesPromoteExistingTintsBeforeEvictingTheLeastRecentEntry() {
        HeadlessRasterScratch().use { scratch ->
            val first = scratch.weights(0x01020304)
            val second = scratch.weights(0x02030405)
            scratch.weights(0x03040506)
            scratch.weights(0x04050607)
            assertSame(first, scratch.weights(0x01020304))
            scratch.weights(0x05060708)
            assertSame(first, scratch.weights(0x01020304))
            assertNotSame(second, scratch.weights(0x02030405))
        }
    }

    @Test
    fun sharedWeightsPreserveChangingSourcesCutoffsClipsAndDestinations() {
        val size = IntSize(128, 64)
        val bounds = IntRect(0, 0, size.width, size.height)
        val clip = IntRect(0, 0, size.width, size.height)
        HeadlessRasterScratch().use { scratch ->
            for (generation in 0..2) {
                val image =
                    createDrawImage(
                        IntSize(16, 16),
                        IntArray(256) { index ->
                            (((index * 37 + generation) and 255) shl 24) or ((index * 73471 + generation) and 0xFFFFFF)
                        },
                    )
                for (tint in listOf(-1, 0xFFBFD7EF.toInt(), 0x80A4C6E8.toInt(), 0x01020406, 0x7F8081FE)) {
                    for (orientation in SampledImageOrientation.entries) {
                        for (cutoff in listOf(0f, Math.nextDown(0.5f), 0.5f, Math.nextUp(0.5f), 1f)) {
                            val command = DrawCommand.SampledImage(image, FloatRect(0.125f, 0.25f, 15.875f, 15.75f), FloatRect(-0.5f, -0.25f, 128.25f, 64.5f), ArgbColor(tint), cutoff, orientation)
                            val initial = IntArray(128 * 64) { index -> ((index * 17 and 255) shl 24) or (index * 3719 and 0xFFFFFF) }
                            val expected = initial.copyOf()
                            SampledImageRasterizer.paint(expected, size, 1, command, clip)
                            val actual = initial.copyOf()
                            SampledImageRasterizer.paint(actual, size, 1, command, clip, scratch = scratch)
                            assertArrayEquals(expected, actual)
                            val commands = listOf(DrawCommand.PushFractionalClip(FloatRect(1.25f, 0.75f, 126.5f, 63.25f)), command, DrawCommand.PopClip)
                            val owned = rasterizeHeadless(commands, size).copyArgb()
                            val borrowed = IntArray(owned.size) { -1 }
                            rasterizeHeadlessInto(commands, bounds, 1, borrowed, scratch)
                            assertArrayEquals(owned, borrowed)
                        }
                    }
                }
            }
            assertTrue(scratch.retainedBytes <= 256 * 1024)
        }
    }

    @Test
    fun closedAndForeignThreadScratchRejectsUseBeforeChangingTheRaster() {
        val scratch = HeadlessRasterScratch()
        val failure = AtomicReference<Throwable>()
        val worker =
            Thread {
                try {
                    scratch.weights(-1)
                } catch (caught: IllegalStateException) {
                    failure.set(caught)
                }
            }
        worker.start()
        worker.join()
        assertTrue(failure.get() is IllegalStateException)
        scratch.close()
        scratch.close()
        val pixels = intArrayOf(0x12345678)
        assertThrows(IllegalStateException::class.java) {
            rasterizeHeadlessInto(emptyList(), IntRect(0, 0, 1, 1), 1, pixels, scratch)
        }
        assertArrayEquals(intArrayOf(0x12345678), pixels)
    }
}
