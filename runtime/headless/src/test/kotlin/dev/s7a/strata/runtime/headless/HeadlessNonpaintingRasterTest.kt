package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies complete nonpainting dispatch against independent ordered pixel-center and blend equations.
 * Borrowed storage is deliberately dirty on every call, including reuse with smaller regions.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessNonpaintingRasterTest {
    private val image = createDrawImage(IntSize(3, 2), intArrayOf(0x001337AA, -1, 0x80445566.toInt(), 0x01020406, 0x407799BB, 0xFF224466.toInt()))
    private val source = IntRect(0, 0, 3, 2)
    private val fractionalSource = FloatRect(0.125f, 0.0625f, 2.875f, 1.9375f)

    @Test
    @Suppress("NestedBlockDepth") // Each independent prefix, invisible command and suffix must retain its original order.
    fun everyPrimitivePreservesPixelsOrderingAndBorrowedOwnershipAcrossEmptyPhysicalCoverage() {
        val bounds = IntRect(180, 60, 197, 73)
        val originalImage = image.copyArgb()
        for (scale in 1..4) {
            val belowCenter = FloatRect(180f, 60f, 180f + 0.25f / scale, 60f + 0.25f / scale)
            val visible = primitives(bounds)
            val hidden =
                primitives(IntRect(197, 60, 202, 73)).map { listOf(it) } +
                    visible.map { listOf(DrawCommand.PushClip(IntRect(180, 60, 180, 73)), it, DrawCommand.PopClip) } +
                    visible.map { listOf(DrawCommand.PushClip(bounds), DrawCommand.PushFractionalClip(belowCenter), it, DrawCommand.PopClip, DrawCommand.PopClip) } +
                    listOf(
                        listOf(DrawCommand.FillRectangle(IntRect(180, 60, 180, 73), ArgbColor(-1))),
                        listOf(DrawCommand.SampledImage(image, fractionalSource, belowCenter)),
                        listOf(DrawCommand.SampledImage(image, fractionalSource, FloatRect(180f, 60f, 197f, 73f), ArgbColor(0x001337AA), 0f)),
                        emptyList(),
                    )
            val before = listOf(emptyList(), listOf(DrawCommand.BlitImage(image, source, bounds)))
            val after =
                listOf(
                    emptyList(),
                    listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0xFF345678.toInt()))),
                    listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0x80345678.toInt())), DrawCommand.FillRectangle(bounds, ArgbColor(0x407799BB))),
                    primitives(IntRect(181, 61, 182, 62)),
                )
            for (prefix in before) {
                for (commands in hidden + listOf(hidden.flatten(), visible)) {
                    for (suffix in after) verify(prefix + commands + suffix, bounds, scale)
                }
            }
        }
        assertArrayEquals(originalImage, image.copyArgb())
    }

    @Test
    @Suppress("NestedBlockDepth") // Enumerate exact Float-edge pairs and orientations without approximating pixel coverage.
    fun fractionalEdgesKeepExactPhysicalCentersAndAbsoluteSourceMapping() {
        val bounds = IntRect(180, 60, 184, 64)
        for (scale in 1..4) {
            val center = 181f + 0.5f / scale
            val edges = listOf(Math.nextDown(center), center, Math.nextUp(center))
            for (left in edges) {
                for (right in edges.filter { left < it }) {
                    for (orientation in SampledImageOrientation.entries) {
                        val destination = FloatRect(left, 60.125f, right, 63.875f)
                        val sampled = DrawCommand.SampledImage(image, fractionalSource, destination, ArgbColor(0x807193B5.toInt()), 0.001f, orientation)
                        val commands =
                            listOf(
                                DrawCommand.FillRectangle(bounds, ArgbColor(0x40557799)),
                                DrawCommand.PushFractionalClip(FloatRect(180.125f, 60.0625f, right, 63.9375f)),
                                DrawCommand.PushClip(bounds),
                                sampled,
                                DrawCommand.PopClip,
                                DrawCommand.PopClip,
                                DrawCommand.FillRectangle(IntRect(181, 61, 183, 63), ArgbColor(0x01020406)),
                            )
                        verify(commands, bounds, scale)
                    }
                }
            }
            val huge = IntRect(-Int.MAX_VALUE + 184, 60, 184, 64)
            verify(primitives(huge), bounds, scale)
        }
    }

    @Test
    fun zeroTintDoesNotBroadenIntoTransparentIntegerImageOrFillSkipping() {
        val bounds = IntRect(0, 0, 3, 2)
        val transparentImage = createDrawImage(IntSize(1, 1), intArrayOf(0x001337AA))
        val tint = DrawCommand.SampledImage(image, fractionalSource, FloatRect(0f, 0f, 3f, 2f), ArgbColor(0x001337AA), 0f)
        val commands =
            listOf(
                DrawCommand.FillRectangle(bounds, ArgbColor(0x01020304)),
                tint,
                DrawCommand.FillRectangle(bounds, ArgbColor(0x001337AA)),
                DrawCommand.BlitImage(transparentImage, IntRect(0, 0, 1, 1), bounds),
                DrawCommand.BlitImagePixels(transparentImage, IntRect(0, 0, 1, 1), bounds),
            )
        for (scale in 1..4) {
            verify(commands, bounds, scale)
            val dirty = IntArray(6 * scale * scale + 7) { 0x001337AA }
            rasterizeHeadlessInto(listOf(tint, DrawCommand.FillRectangle(bounds, ArgbColor(0x007799BB))), bounds, scale, dirty)
            assertArrayEquals(IntArray(6 * scale * scale), dirty.copyOf(6 * scale * scale))
            assertArrayEquals(IntArray(7) { 0x001337AA }, dirty.takeLast(7).toIntArray())
        }
    }

    @Test
    fun fullFillsAfterNonpaintingPrimitivesKeepIntermediateRoundingAtEveryAdmissionBoundary() {
        val colors = listOf(0x01020304, 0x80345678.toInt(), 0x407799BB, 0x00AABBCC)
        val result = colors.fold(0) { destination, color -> HeadlessScalarRaster.blendInteger(color, destination) }
        for (area in listOf(4095, 4096, 4097, 262143, 262144, 262145, 1048575, 1048576, 1048577)) {
            val bounds = IntRect(12, 10, 12 + area, 11)
            val commands = primitives(IntRect(12 + area, 10, 13 + area, 11)) + colors.map { DrawCommand.FillRectangle(bounds, ArgbColor(it)) }
            val expected = IntArray(area) { result }
            val borrowed = IntArray(area + 11) { 0x12345678 }
            rasterizeHeadlessInto(commands, bounds, 1, borrowed)
            assertArrayEquals(expected, borrowed.copyOf(area))
            assertArrayEquals(IntArray(11) { 0x12345678 }, borrowed.takeLast(11).toIntArray())
            assertArrayEquals(expected, rasterizeHeadlessRegion(commands, bounds, 1).copyArgb())
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST") // Reproduce a Java-origin null after valid invisible and visible commands.
    fun wholeListPreflightRejectsLaterFailuresBeforeWritingBorrowedStorage() {
        val bounds = IntRect(0, 0, 4, 4)
        val prefix = primitives(IntRect(4, 0, 8, 4)) + DrawCommand.FillRectangle(bounds, ArgbColor(-1))
        val malformed =
            listOf(
                prefix + DrawCommand.Platform(TestPlatformCommand, bounds),
                prefix + DrawCommand.PopClip,
                prefix + DrawCommand.PushClip(bounds),
                prefix + DrawCommand.PushFractionalClip(FloatRect(0.1f, 0.1f, 0.2f, 0.2f)),
                (prefix + listOf<DrawCommand?>(null)) as List<DrawCommand>,
            )
        val pixels = IntArray(19) { 0x12345678 }
        val expected = pixels.copyOf()
        for (commands in malformed) {
            assertThrows<IllegalArgumentException> { rasterizeHeadlessInto(commands, bounds, 1, pixels) }
            assertArrayEquals(expected, pixels)
        }
        for (region in listOf(IntRect(-1, 0, 1, 1), IntRect(0, 0, 0, 4), IntRect(0, 0, 5, 5))) {
            assertThrows<IllegalArgumentException> { rasterizeHeadlessInto(prefix, region, 1, pixels) }
            assertArrayEquals(expected, pixels)
        }
        assertThrows<IllegalArgumentException> { rasterizeHeadlessInto(prefix, bounds, 0, pixels) }
        assertThrows<ArithmeticException> { rasterizeHeadlessInto(prefix, IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1), 2, pixels) }
        assertArrayEquals(expected, pixels)
    }

    private fun primitives(bounds: IntRect): List<DrawCommand> =
        listOf(
            DrawCommand.FillRectangle(bounds, ArgbColor(0x80345678.toInt())),
            DrawCommand.BlitImage(image, source, bounds),
            DrawCommand.BlitImagePixels(image, source, bounds),
            DrawCommand.SampledImage(image, fractionalSource, FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()), ArgbColor(0x807193B5.toInt()), 0.001f, SampledImageOrientation.FlipBoth),
        )

    private fun verify(
        commands: List<DrawCommand>,
        bounds: IntRect,
        scale: Int,
    ) {
        val original = commands.toList()
        val expected = HeadlessScalarRaster.paint(commands, bounds, scale)
        val immutable = rasterizeHeadlessRegion(commands, bounds, scale)
        assertArrayEquals(expected, immutable.copyArgb(), "$bounds/$scale/$commands")
        val borrowed = IntArray(expected.size + 13) { 0x001337AA }
        repeat(2) {
            borrowed.fill(0x001337AA)
            rasterizeHeadlessInto(commands, bounds, scale, borrowed)
            assertArrayEquals(expected, borrowed.copyOf(expected.size))
            assertArrayEquals(IntArray(13) { 0x001337AA }, borrowed.takeLast(13).toIntArray())
        }
        val previousTail = borrowed.copyOfRange(scale * scale, borrowed.size)
        rasterizeHeadlessInto(emptyList(), IntRect(1, 1, 2, 2), scale, borrowed)
        assertArrayEquals(IntArray(scale * scale), borrowed.copyOf(scale * scale))
        assertArrayEquals(previousTail, borrowed.copyOfRange(scale * scale, borrowed.size))
        assertArrayEquals(expected, immutable.copyArgb())
        assertEquals(original, commands)
    }

    private data object TestPlatformCommand : PlatformDrawCommand
}
