package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.util.concurrent.Executors

/**
 * Requires complete integer rasters to match independent rational coordinates and ordered Long source-over.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessIntegerColumnsTest {
    @Test
    fun bothPathsKeepExactCoordinatesAndEveryDestinationSubpixelAtAllNormalDensities() {
        val bounds = IntRect(19, 13, 115, 93)
        val source = createDrawImage(IntSize(83, 79)) { x, y -> ((x % 3 * 127) shl 24) or (x * 971 + y * 73471 and 0xFFFFFF) }
        val crop = IntRect(2, 3, 69, 50)
        val destinations = listOf(IntRect(-13, -9, 126, 101), IntRect(21, 15, 112, 88), IntRect(19, 13, 86, 60), IntRect(21, 15, 27, 18))
        for (density in 1..4) {
            for (physical in listOf(false, true)) {
                for (destination in destinations) {
                    val scene = Scene(bounds, density, source, crop, destination, physical)
                    verify(scene)
                }
            }
        }
    }

    @Test
    fun opaqueTexelsExposeSelectedSourceCoordinatesIncludingNearestTies() {
        val source = createDrawImage(IntSize(79, 73)) { x, y -> 0xFF000000.toInt() or (x shl 12) or y }
        for (density in 1..4) {
            for (physical in listOf(false, true)) {
                verify(Scene(IntRect(0, 0, 96, 80), density, source, IntRect(1, 2, 78, 71), IntRect(-7, -9, 108, 96), physical))
            }
        }
    }

    @Test
    fun integerOverlaysPreservePreviouslyFlippedFloatSubpixelDetail() {
        val image = createDrawImage(IntSize(67, 43)) { x, y -> 0x80000000.toInt() or (x * 971 + y * 73471 and 0xFFFFFF) }
        for (density in 1..4) {
            for (physical in listOf(false, true)) {
                verify(Scene(IntRect(19, 13, 115, 93), density, image, IntRect(0, 0, 67, 43), IntRect(8, 3, 128, 105), physical), true)
            }
        }
    }

    @Test
    fun compactCompleteRasterExercisesBigIntegerRatherThanOneTexelDelegation() {
        val source = createDrawImage(IntSize(8_388_609, 1)) { x, _ -> 0xFF000000.toInt() or (x * 73471 and 0xFFFFFF) }
        val destination = IntRect(Int.MIN_VALUE + 2, 0, 1, 1)
        val density = 256
        val command = DrawCommand.BlitImagePixels(source, IntRect(0, 0, source.size.width, 1), destination)
        val background = 0x804A6789.toInt()
        val clips = listOf(DrawCommand.PushClip(IntRect(0, 0, 1, 1)), DrawCommand.PushFractionalClip(FloatRect(0f, 0f, 0.25f, 0.25f)))
        val commands = listOf(DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(background))) + clips + command + listOf(DrawCommand.PopClip, DrawCommand.PopClip)
        val expected = IntArray(256 * 256) { background }
        for (x in 0 until 64) {
            val center = BigInteger.valueOf(x.toLong()).subtract(BigInteger.valueOf(destination.left.toLong()).multiply(BigInteger.valueOf(256))).multiply(BigInteger.TWO).add(BigInteger.ONE)
            val numerator = center.multiply(BigInteger.valueOf(8_388_609))
            if (x == 0) {
                assertEquals(BigInteger("1099511626753"), center)
                assertEquals(BigInteger("9223373127784856577"), numerator)
            }
            assertTrue(BigInteger.valueOf(Long.MAX_VALUE) < numerator)
            val selected = coordinate(x, 256, 0 until source.size.width, destination.left until destination.right)
            assertEquals(8_388_608, selected)
            for (y in 0 until 64) expected[y * 256 + x] = blend(source.argbAt(selected, 0), background)
        }
        assertArrayEquals(expected, rasterizeHeadless(commands, IntSize(1, 1), density).copyArgb())
        val borrowed = IntArray(expected.size + 11) { 0x12345678 }
        rasterizeHeadlessInto(commands, IntRect(0, 0, 1, 1), density, borrowed)
        assertArrayEquals(expected, borrowed.copyOf(expected.size))
        assertArrayEquals(IntArray(11) { 0x12345678 }, borrowed.copyOfRange(expected.size, borrowed.size))
    }

    @Test
    fun preflightAndClosedScratchRejectBeforeBorrowedStorageChanges() {
        val source = createDrawImage(IntSize(67, 43)) { x, y -> 0x80000000.toInt() or (x * 971 + y * 73471 and 0xFFFFFF) }
        val bounds = IntRect(0, 0, 96, 64)
        val command = DrawCommand.BlitImage(source, IntRect(0, 0, 67, 43), bounds)
        val storage = IntArray(96 * 64 + 7) { 0x12345678 }
        val original = storage.copyOf()
        assertThrows<IllegalArgumentException> { rasterizeHeadlessInto(listOf(command, DrawCommand.PopClip), bounds, 1, storage) }
        assertArrayEquals(original, storage)
        val scratch = HeadlessRasterScratch()
        scratch.close()
        assertThrows<IllegalStateException> { rasterizeHeadlessInto(listOf(command), bounds, 1, storage, scratch) }
        assertArrayEquals(original, storage)
        assertThrows<ArithmeticException> { rasterizeHeadlessInto(listOf(command), IntRect(Int.MAX_VALUE - 2, 0, Int.MAX_VALUE, 1), 2, storage) }
        assertArrayEquals(original, storage)
    }

    private fun verify(scene: Scene, flipped: Boolean = false) {
        val size = IntSize(scene.bounds.width * scene.density, scene.bounds.height * scene.density)
        val background =
            createDrawImage(size) { x, y ->
                val alpha = if (flipped) 255 else x % 3 * 127
                (alpha shl 24) or (x * 971 + y * 73471 and 0xFFFFFF)
            }
        val clip = IntRect(scene.bounds.left + 2, scene.bounds.top + 2, scene.bounds.right - 2, scene.bounds.bottom - 2)
        val fractional = FloatRect(clip.left + 0.125f, clip.top + 0.375f, clip.right - 0.375f, clip.bottom - 0.125f)
        val overlay =
            if (scene.physical) DrawCommand.BlitImagePixels(scene.image, scene.source, scene.destination) else DrawCommand.BlitImage(scene.image, scene.source, scene.destination)
        val previous =
            if (flipped) {
                DrawCommand.SampledImage(
                    background,
                    FloatRect(0f, 0f, size.width.toFloat(), size.height.toFloat()),
                    FloatRect(scene.bounds.left.toFloat(), scene.bounds.top.toFloat(), scene.bounds.right.toFloat(), scene.bounds.bottom.toFloat()),
                    orientation = SampledImageOrientation.FlipBoth,
                )
            } else {
                DrawCommand.BlitImagePixels(background, IntRect(0, 0, size.width, size.height), scene.bounds)
            }
        val commands = listOf(previous, DrawCommand.PushClip(clip), DrawCommand.PushFractionalClip(fractional), overlay, DrawCommand.PopClip, DrawCommand.PopClip)
        val expected = reference(scene, background, fractional, flipped)
        val first = rasterizeHeadlessRegion(commands, scene.bounds, scene.density)
        assertArrayEquals(expected, first.copyArgb())
        val borrowed = IntArray(expected.size + 7) { 0x12345678 }
        rasterizeHeadlessInto(commands, scene.bounds, scene.density, borrowed)
        assertArrayEquals(expected, borrowed.copyOf(expected.size))
        assertArrayEquals(IntArray(7) { 0x12345678 }, borrowed.copyOfRange(expected.size, borrowed.size))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val second = executor.submit<IntArray> { rasterizeHeadlessRegion(commands, scene.bounds, scene.density).copyArgb() }
            assertArrayEquals(expected, second.get())
        } finally {
            executor.shutdownNow()
        }
        assertArrayEquals(expected, first.copyArgb())
    }

    private fun reference(scene: Scene, background: DrawImage, clip: FloatRect, flipped: Boolean): IntArray {
        val width = background.size.width
        return IntArray(width * background.size.height) { index ->
            val x = index % width + scene.bounds.left * scene.density
            val y = index / width + scene.bounds.top * scene.density
            val destination = scene.destination
            val previousX = if (flipped) width - 1 - index % width else index % width
            val previousY = if (flipped) background.size.height - 1 - index / width else index / width
            val previous = blend(background.argbAt(previousX, previousY), 0)
            if (covered(scene, clip, x, y).not()) {
                previous
            } else {
                val density = if (scene.physical) scene.density else 1
                val sourceX = coordinate(if (scene.physical) x else x / scene.density, density, scene.source.left until scene.source.right, destination.left until destination.right)
                val sourceY = coordinate(if (scene.physical) y else y / scene.density, density, scene.source.top until scene.source.bottom, destination.top until destination.bottom)
                blend(scene.image.argbAt(sourceX, sourceY), previous)
            }
        }
    }

    private fun covered(scene: Scene, clip: FloatRect, x: Int, y: Int): Boolean {
        val centerX = (x.toDouble() + 0.5) / scene.density
        val centerY = (y.toDouble() + 0.5) / scene.density
        val destination = scene.destination
        return clip.left <= centerX && centerX < clip.right && clip.top <= centerY && centerY < clip.bottom &&
            destination.left <= x / scene.density && x / scene.density < destination.right &&
            destination.top <= y / scene.density && y / scene.density < destination.bottom
    }

    private fun coordinate(position: Int, density: Int, source: IntRange, destination: IntRange): Int {
        val origin = BigInteger.valueOf(destination.first.toLong()).multiply(BigInteger.valueOf(density.toLong()))
        val center = BigInteger.valueOf(position.toLong()).subtract(origin).multiply(BigInteger.TWO).add(BigInteger.ONE)
        val numerator = center.multiply(BigInteger.valueOf(source.last.toLong() - source.first + 1))
        val denominator = BigInteger.valueOf(destination.last.toLong() - destination.first + 1).multiply(BigInteger.valueOf(density.toLong())).multiply(BigInteger.TWO)
        return numerator.divide(denominator).add(BigInteger.valueOf(source.first.toLong())).intValueExact()
    }

    private fun blend(source: Int, destination: Int): Int {
        val sa = (source ushr 24).toLong()
        val da = (destination ushr 24).toLong()
        val alpha = sa * 255 + da * (255 - sa)
        if (alpha == 0L) return 0
        var result = (((alpha + 127) / 255).toInt() shl 24)
        for (shift in 16 downTo 0 step 8) {
            val sc = (source ushr shift and 255).toLong()
            val dc = (destination ushr shift and 255).toLong()
            result = result or (((sc * sa * 255 + dc * da * (255 - sa) + alpha / 2) / alpha).toInt() shl shift)
        }
        return result
    }

    private data class Scene(val bounds: IntRect, val density: Int, val image: DrawImage, val source: IntRect, val destination: IntRect, val physical: Boolean)
}
