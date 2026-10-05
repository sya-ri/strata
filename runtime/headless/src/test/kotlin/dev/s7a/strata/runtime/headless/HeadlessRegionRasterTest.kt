package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies exact original-coordinate sampling with storage bounded to a visible region.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessRegionRasterTest {
    private val image = createDrawImage(IntSize(6, 4), IntArray(24) { if (it % 6 == 0) 0xFFFF0000.toInt() else 0x8000FF00.toInt() })
    private val region = IntRect(180, 60, 185, 65)
    private val sampled = DrawCommand.SampledImage(image, FloatRect(0f, 0.25f, 6f, 1.75f), FloatRect(180f, 60f, 185f, 65f), alphaCutoff = 0f)

    @Test
    fun fractionalBoundaryKeepsGlobalFloatSelectionAtNonPowerOfTwoDensity() {
        val commands = listOf(DrawCommand.FillRectangle(region, ArgbColor(0xFF000000.toInt())), sampled)
        val output = rasterizeHeadlessRegion(commands, region, 3)
        assertEquals(IntSize(15, 15), output.size)
        assertEquals(0xFFFF0000.toInt(), output.argbAt(2, 0))
        assertMatchesFullCrop(commands, region, 3)
    }

    @Test
    fun everyPrimitiveClipTintFlipAndCutoffMatchesOriginalFullRaster() {
        for (scale in 1..4) {
            for (orientation in SampledImageOrientation.entries) {
                val commands =
                    listOf(
                        DrawCommand.FillRectangle(IntRect(175, 55, 190, 70), ArgbColor(0x806789AB.toInt())),
                        DrawCommand.BlitImage(image, IntRect(0, 0, 6, 4), IntRect(178, 58, 186, 66)),
                        DrawCommand.PushClip(IntRect(180, 59, 186, 66)),
                        DrawCommand.PushFractionalClip(FloatRect(180.25f, 60.1f, 184.9f, 64.75f)),
                        sampled.copy(tint = ArgbColor(0x806655AA.toInt()), alphaCutoff = 0.1f, orientation = orientation),
                        DrawCommand.FillRectangle(IntRect(181, 61, 184, 64), ArgbColor(0x4088AA33)),
                        DrawCommand.BlitImagePixels(image, IntRect(1, 0, 5, 4), IntRect(179, 60, 186, 65)),
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                    )
                assertMatchesFullCrop(commands, region, scale)
            }
            assertMatchesFullCrop(listOf(DrawCommand.BlitImage(image, IntRect(0, 0, 6, 4), IntRect(180, 60, 186, 64))), IntRect(180, 60, 186, 64), scale)
            val single = createDrawImage(IntSize(1, 1), intArrayOf(0x8066AA22.toInt()))
            assertMatchesFullCrop(listOf(sampled.copy(image = single, source = FloatRect(0f, 0f, 1f, 1f))), region, scale)
        }
    }

    @Test
    fun invalidRegionsAndClipsAreRejected() {
        assertThrows<IllegalArgumentException> { rasterizeHeadlessRegion(listOf(sampled), IntRect(-1, 0, 4, 5), 1) }
        assertThrows<IllegalArgumentException> { rasterizeHeadlessRegion(listOf(sampled), IntRect(0, 0, 0, 5), 1) }
        assertThrows<ArithmeticException> { rasterizeHeadlessRegion(listOf(sampled), IntRect(Int.MAX_VALUE - 2, 0, Int.MAX_VALUE, 1), 2) }
        assertThrows<IllegalArgumentException> { rasterizeHeadlessRegion(listOf(DrawCommand.PopClip), region, 1) }
    }

    private fun assertMatchesFullCrop(
        commands: List<DrawCommand>,
        bounds: IntRect,
        scale: Int,
    ) {
        val full = rasterizeHeadless(commands, IntSize(200, 80), scale)
        val crop = rasterizeHeadlessRegion(commands, bounds, scale)
        assertEquals(IntSize(bounds.width * scale, bounds.height * scale), crop.size)
        for (y in 0 until crop.size.height) {
            for (x in 0 until crop.size.width) {
                assertEquals(full.argbAt(bounds.left * scale + x, bounds.top * scale + y), crop.argbAt(x, y), "Region $bounds at scale $scale pixel ($x,$y)")
            }
        }
    }
}
