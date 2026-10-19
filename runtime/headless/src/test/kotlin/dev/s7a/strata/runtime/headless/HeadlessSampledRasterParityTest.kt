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
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Compares constant-image and mapped-row sampling with independent per-pixel coverage and composition.
 * The reference never calls the optimized painter or shares its row mapping and constant-color shortcuts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessSampledRasterParityTest {
    @Test
    fun whiteSourceOverOpaqueTintPreservesEveryChannelAndBothAlphaValues() {
        val size = IntSize(1, 256)
        val image = createDrawImage(size, IntArray(256) { alpha -> (alpha shl 24) or 0xFFFFFF })
        val bounds = FloatRect(0f, 0f, 1f, 256f)
        val clip = IntRect(0, 0, 1, 256)
        for (alpha in 0..255) {
            for (channel in 0..255) {
                val tint = (alpha shl 24) or (channel shl 16) or ((channel * 37 and 255) shl 8) or (channel * 73 and 255)
                val command = DrawCommand.SampledImage(image, bounds, bounds, ArgbColor(tint), 0f)
                val background = IntArray(256) { tint or 0xFF000000.toInt() }
                assertArrayEquals(background, reference(background, size, 1, command, clip))
                val actual = background.copyOf()
                SampledImageRasterizer.paint(actual, size, 1, command, clip)
                assertArrayEquals(background, actual)
            }
        }
    }

    @Test
    fun everyAlphaPairPreservesOrderedFloatComposition() {
        val size = IntSize(256, 256)
        val source = IntArray(size.width * size.height) { index -> ((index / 256) shl 24) or (index * 73471 and 0xFFFFFF) }
        val background = IntArray(source.size) { index -> ((index % 256) shl 24) or (index * 1973 and 0xFFFFFF) }
        val image = createDrawImage(size, source)
        val bounds = FloatRect(0f, 0f, 256f, 256f)
        val clip = IntRect(0, 0, 256, 256)
        for (tint in listOf(-1, 0xFD7195B3.toInt(), 0x80A4C6E8.toInt(), 0x01020406)) {
            val command = DrawCommand.SampledImage(image, bounds, bounds, ArgbColor(tint), 0f)
            val actual = background.copyOf()
            SampledImageRasterizer.paint(actual, size, 1, command, clip)
            val expected = reference(background, size, 1, command, clip)
            assertArrayEquals(expected, actual)
            HeadlessRasterScratch().use { scratch ->
                val reused = background.copyOf()
                SampledImageRasterizer.paint(reused, size, 1, command, clip, scratch = scratch)
                assertArrayEquals(expected, reused)
            }
        }
        assertArrayEquals(source, image.copyArgb())
    }

    @Test
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod") // Keep the finite geometry/color matrix in one parity assertion.
    fun fractionalSamplingPreservesEveryCoveredPixel() {
        val viewport = IntSize(48, 24)
        val sources = listOf(0x007195B3, 0x017195B3, 0x807195B3.toInt(), 0xFF7195B3.toInt(), -1)
        for (density in 1..4) {
            val physical = IntSize(viewport.width * density, viewport.height * density)
            val backgrounds =
                IntArray(physical.width * physical.height) { index ->
                    ((index * 37 and 255) shl 24) or (index * 73471 and 0xFFFFFF)
                }
            for (source in sources + listOf<Int?>(null)) {
                val imageSize = if (source == null) IntSize(7, 5) else IntSize(1, 1)
                val input = IntArray(imageSize.width * imageSize.height) { source ?: sources[it % sources.size] }
                val image = createDrawImage(imageSize, input)
                val sourceBounds = FloatRect(0.125f, 0.0625f, imageSize.width - 0.0625f, imageSize.height - 0.125f)
                val destinations =
                    listOf(
                        FloatRect(-0.75f, -1.25f, 49.5f, 25.25f),
                        FloatRect(1.125f, 2.625f, 46.875f, 23.125f),
                        FloatRect(2.375f, 1.125f, 3.625f, 2.875f),
                    )
                for (background in listOf(backgrounds, IntArray(backgrounds.size), IntArray(backgrounds.size) { 0x001337AA }, IntArray(backgrounds.size) { 0x804A6789.toInt() })) {
                    for (destination in destinations) {
                        for (orientation in SampledImageOrientation.entries) {
                            for (tint in listOf(-1, 0xFF80FF40.toInt(), 0x80A4C6E8.toInt(), 0x00BFD7EF, 0x01020406)) {
                                for (cutoff in listOf(0f, 0.1f, 0.5f, 1f)) {
                                    val command = DrawCommand.SampledImage(image, sourceBounds, destination, ArgbColor(tint), cutoff, orientation)
                                    for (clip in listOf(IntRect(0, 0, physical.width, physical.height), IntRect(2, 3, physical.width - 3, physical.height - 2))) {
                                        val expected = reference(background, physical, density, command, clip)
                                        val actual = background.copyOf()
                                        SampledImageRasterizer.paint(actual, physical, density, command, clip)
                                        assertArrayEquals(expected, actual, "$density/$source/$destination/$orientation/$tint/$cutoff/$clip")
                                    }
                                }
                            }
                        }
                    }
                }
                assertArrayEquals(input, image.copyArgb())
            }
        }
    }

    @Test
    fun denseChannelReusePreservesDestinationChangesAndEverySourceByte() {
        val physical = IntSize(128, 64)
        val imageSize = IntSize(64, 64)
        val source =
            IntArray(imageSize.width * imageSize.height) { index ->
                ((index * 37 and 255) shl 24) or (index * 73471 and 0xFFFFFF)
            }
        val image = createDrawImage(imageSize, source)
        val uniform = IntArray(physical.width * physical.height) { 0xFF234567.toInt() }
        val transparent = IntArray(uniform.size) { 0x001337AA }
        val changed = uniform.copyOf().apply { fill(0x80123456.toInt(), size / 3, size * 2 / 3) }
        for (tint in listOf(-1, 0xFFBFD7EF.toInt(), 0x80A4C6E8.toInt(), 0x01020406)) {
            for (background in listOf(uniform, transparent, changed, IntArray(uniform.size))) {
                for (orientation in SampledImageOrientation.entries) {
                    val command = DrawCommand.SampledImage(image, FloatRect(0.125f, 0.0625f, 63.9375f, 63.875f), FloatRect(-0.75f, -1.25f, 129.5f, 65.25f), ArgbColor(tint), 0f, orientation)
                    val clip = IntRect(0, 0, physical.width, physical.height)
                    val actual = background.copyOf()
                    SampledImageRasterizer.paint(actual, physical, 1, command, clip)
                    assertArrayEquals(reference(background, physical, 1, command, clip), actual, "$tint/$orientation")
                }
            }
        }
        assertArrayEquals(source, image.copyArgb())
    }

    @Test
    fun repeatedSourceRowsPreserveChangesAtEitherEndOfTheDestination() {
        val viewport = IntSize(128, 64)
        val imageSize = IntSize(7, 5)
        val input =
            IntArray(35) { index ->
                val alpha = listOf(0, 255, 128, 1, 255)[index / imageSize.width]
                (alpha shl 24) or (index * 73471 and 0xFFFFFF)
            }
        val image = createDrawImage(imageSize, input)
        for (density in 1..4) {
            val physical = IntSize(viewport.width * density, viewport.height * density)
            val background =
                IntArray(physical.width * physical.height) { index ->
                    val x = index % physical.width
                    val y = index / physical.width
                    when {
                        y % 7 == 3 && x == 2 -> 0x017195B3
                        y % 7 == 4 && x == physical.width - 3 -> 0x80123456.toInt()
                        else -> 0xFF000000.toInt() or (x * 173 and 0xFFFFFF)
                    }
                }
            for (orientation in SampledImageOrientation.entries) {
                for (tint in listOf(-1, 0xFFBFD7EF.toInt(), 0x80A4C6E8.toInt())) {
                    for (cutoff in listOf(0f, 0.1f, 0.5f, 1f)) {
                        val command =
                            DrawCommand.SampledImage(
                                image,
                                FloatRect(0.125f, 0.0625f, 6.9375f, 4.875f),
                                FloatRect(-0.75f, -1.25f, 129.5f, 65.25f),
                                ArgbColor(tint),
                                cutoff,
                                orientation,
                            )
                        val clip = IntRect(2, 3, physical.width - 2, physical.height - 3)
                        val actual = background.copyOf()
                        SampledImageRasterizer.paint(actual, physical, density, command, clip)
                        assertArrayEquals(reference(background, physical, density, command, clip), actual, "$density/$orientation/$tint/$cutoff")
                    }
                }
            }
        }
        assertArrayEquals(input, image.copyArgb())
    }

    @Test
    @Suppress("NestedBlockDepth") // Compare the independent oracle across destination admission and alpha boundaries.
    fun destinationPaletteAdmissionPreservesUniformEarlyChangedAndPatternedPixels() {
        val size = IntSize(96, 64)
        val rectangle = FloatRect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val destination = FloatRect(-0.125f, -0.25f, size.width + 0.375f, size.height + 0.25f)
        val clip = IntRect(0, 0, size.width, size.height)
        val area = size.width * size.height
        val alphaBytes = listOf(0, 1, 2, 63, 64, 65, 126, 127, 128, 129, 190, 191, 192, 253, 254, 255)
        for (alphas in listOf(1, 16, 256)) {
            val input =
                IntArray(area) { index ->
                    val alpha =
                        if (alphas == 1) {
                            128
                        } else if (alphas == 16) {
                            alphaBytes[index % alphaBytes.size]
                        } else {
                            index and 255
                        }
                    (alpha shl 24) or (index * 73471 and 0xFFFFFF)
                }
            val image = createDrawImage(size, input)
            val backgrounds =
                listOf(0, 0x001337AA, 0x804A6789.toInt(), 0xFF234567.toInt()).map { color -> IntArray(area) { color } } +
                    listOf(IntArray(area) { index -> ((index * 37 and 255) shl 24) or (index * 1973 and 0xFFFFFF) }) +
                    listOf(1, 16, 31, 32, 33, 64).map { prefix -> IntArray(area) { if (it < prefix) 0xFF234567.toInt() else 0x80123456.toInt() } }
            for (tint in listOf(-1, 0xFFBFD7EF.toInt(), 0x80A4C6E8.toInt(), 0x01020406)) {
                val boundary = 128f / 255f * ((tint ushr 24).toFloat() / 255f)
                for (cutoff in listOf(0f, Math.nextDown(boundary), boundary, Math.nextUp(boundary), 1f)) {
                    val command = DrawCommand.SampledImage(image, rectangle, destination, ArgbColor(tint), cutoff)
                    for (background in backgrounds) {
                        val expected = reference(background, size, 1, command, clip)
                        HeadlessRasterScratch().use { scratch ->
                            val actual = background.copyOf()
                            SampledImageRasterizer.paint(actual, size, 1, command, clip, scratch = scratch)
                            assertArrayEquals(expected, actual, "$alphas/$tint/$cutoff")
                        }
                    }
                }
            }
            assertArrayEquals(input, image.copyArgb())
        }
    }

    private fun reference(
        background: IntArray,
        size: IntSize,
        density: Int,
        command: DrawCommand.SampledImage,
        clip: IntRect,
    ): IntArray =
        IntArray(background.size) { index ->
            val px = index % size.width
            val py = index / size.width
            val x = (px.toDouble() + 0.5) / density
            val y = (py.toDouble() + 0.5) / density
            val destination = command.destination
            val clippedX = px < clip.left || clip.right <= px
            val clippedY = py < clip.top || clip.bottom <= py
            val outsideX = x < destination.left || destination.right <= x
            val outsideY = y < destination.top || destination.bottom <= y
            val clipped = clippedX || clippedY
            val outside = outsideX || outsideY
            if (clipped || outside) {
                background[index]
            } else {
                val rx = (((px.toFloat() + 0.5f) / density) - destination.left) / (destination.right - destination.left)
                val ry = (((py.toFloat() + 0.5f) / density) - destination.top) / (destination.bottom - destination.top)
                val sx = if (command.orientation.flipX) command.source.right * (1f - rx) + command.source.left * rx else command.source.left * (1f - rx) + command.source.right * rx
                val sy = if (command.orientation.flipY) command.source.bottom * (1f - ry) + command.source.top * ry else command.source.top * (1f - ry) + command.source.bottom * ry
                val source = command.image.argbAt(floor(sx).toInt().coerceIn(0, command.image.size.width - 1), floor(sy).toInt().coerceIn(0, command.image.size.height - 1))
                blend(source, background[index], command.tint.value, command.alphaCutoff)
            }
        }

    private fun blend(
        source: Int,
        destination: Int,
        tint: Int,
        cutoff: Float,
    ): Int {
        val alpha = channel(source, 24) * channel(tint, 24)
        if (alpha < cutoff || alpha == 0f) return destination
        val weight = channel(destination, 24) * (1f - alpha)
        val outputAlpha = alpha + weight
        val alphaByte = (outputAlpha * 255f).roundToInt().coerceIn(0, 255)
        if (alphaByte == 0) return 0
        var result = alphaByte shl 24
        for (shift in listOf(16, 8, 0)) {
            val value = (channel(source, shift) * channel(tint, shift) * alpha + channel(destination, shift) * weight) / outputAlpha
            result = result or ((value * 255f).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }

    private fun channel(
        color: Int,
        shift: Int,
    ): Float = (color ushr shift and 255).toFloat() / 255f
}
