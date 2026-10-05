package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the pure portable-image key and checked logical-to-physical extent contract without a native device.
 */
internal class FabricMinecraftPortableImageTest {
    @Test
    fun changedIntegerClipAndFillEdgesOutsideTheImageRetainExactPixels() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(0xFF123456.toInt(), 0x80123456.toInt(), 0x40ABCDEF, -1))

        fun commands(
            clip: IntRect,
            fill: IntRect,
            x: Int,
            y: Int,
        ) = listOf(
            DrawCommand.PushClip(clip),
            DrawCommand.FillRectangle(fill, ArgbColor(0x40ABCDEF)),
            DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(x + 1f, y + 1f, x + 5f, y + 5f), ArgbColor(0xC0AABBCC.toInt()), 0.2f),
            DrawCommand.PopClip,
        )
        for (scale in 1..4) {
            val original = FabricMinecraftPortableImage(commands(IntRect(0, 0, 640, 480), IntRect(170, 60, 210, 100), 180, 70), IntSize(12, 8), scale, IntOffset(180, 70))
            val bounded = FabricMinecraftPortableImage(commands(IntRect(180, 70, 192, 78), IntRect(180, 70, 192, 78), 180, 70), original.size, scale, original.origin)
            val moved = FabricMinecraftPortableImage(commands(IntRect(0, 0, 640, 480), IntRect(0, 0, 32, 28), 0, 0), original.size, scale)
            for (equivalent in listOf(bounded, moved)) {
                assertTrue(original.equivalent(equivalent), "GUI$scale")
                assertArrayEquals(original.rasterize().copyArgb(), equivalent.rasterize().copyArgb())
            }
            val cropped = FabricMinecraftPortableImage(commands(IntRect(181, 70, 192, 78), IntRect(180, 70, 192, 78), 180, 70), original.size, scale, original.origin)
            assertFalse(original.equivalent(cropped))
        }
    }

    @Test
    fun translatedMixedRunsReuseOnlyExactOrderedPixelsAtEveryDensity() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(0xFF123456.toInt(), 0x80123456.toInt(), 0x40ABCDEF, -1))
        val solid = createDrawImage(IntSize(1, 1), intArrayOf(-1))

        fun commands(
            x: Int,
            y: Int,
            orientation: SampledImageOrientation,
        ): List<DrawCommand> =
            listOf(
                DrawCommand.FillRectangle(IntRect(x, y, x + 12, y + 8), ArgbColor(0x80456789.toInt())),
                DrawCommand.PushClip(IntRect(x, y, x + 12, y + 8)),
                DrawCommand.PushFractionalClip(FloatRect(x + 0.25f, y + 0.125f, x + 11.75f, y + 7.875f)),
                DrawCommand.SampledImage(solid, FloatRect(0.125f, 0.25f, 0.875f, 0.75f), FloatRect(x + 1f, y + 1f, x + 11f, y + 7f), ArgbColor(0x40ABCDEF), 0.1f, orientation),
                DrawCommand.BlitImage(image, IntRect(0, 0, 2, 2), IntRect(x + 1, y + 1, x + 5, y + 5)),
                DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(x + 5, y + 1, x + 9, y + 5)),
                DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(x + 1f, y + 1f, x + 5f, y + 5f), ArgbColor(0xC0AABBCC.toInt()), 0.2f, orientation),
                DrawCommand.PopClip,
                DrawCommand.PopClip,
            )
        for (scale in 1..4) {
            for (orientation in SampledImageOrientation.entries) {
                val before = FabricMinecraftPortableImage(commands(0, 0, orientation), IntSize(12, 8), scale)
                val after = FabricMinecraftPortableImage(commands(180, 70, orientation), IntSize(12, 8), scale, IntOffset(180, 70))
                assertTrue(before.equivalent(after), "GUI$scale $orientation")
                assertArrayEquals(before.rasterize().copyArgb(), after.rasterize().copyArgb())
                val changed = after.commands.toMutableList().also { it[0] = DrawCommand.FillRectangle(IntRect(180, 70, 192, 78), ArgbColor(-1)) }
                assertFalse(before.equivalent(FabricMinecraftPortableImage(changed, after.size, scale, after.origin)))
            }
        }
    }

    @Test
    fun changedSamplingCoverageSourceIdentityAndExhaustedProofsRetainRasterization() {
        val image = createDrawImage(IntSize(6, 4), IntArray(24) { -1 })
        val local = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 6f, 4f), FloatRect(1f, 1f, 6f, 6f), alphaCutoff = 0f)
        val moved = local.copy(destination = FloatRect(180f, 70f, 185f, 75f))
        val before = FabricMinecraftPortableImage(listOf(local), IntSize(7, 8), 3)
        val after = FabricMinecraftPortableImage(listOf(moved), IntSize(7, 8), 3, IntOffset(179, 69))
        assertFalse(before.equivalent(after))
        val small = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 6f, 4f), FloatRect(0f, 0f, 12f, 8f), alphaCutoff = 0f)
        val shifted = small.copy(destination = FloatRect(180f, 70f, 192f, 78f))
        val first = FabricMinecraftPortableImage(listOf(small), IntSize(12, 8), 1)

        fun next(command: DrawCommand.SampledImage) = FabricMinecraftPortableImage(listOf(command), IntSize(12, 8), 1, IntOffset(180, 70))
        assertTrue(first.equivalent(next(shifted)))
        assertFalse(first.equivalent(next(shifted.copy(destination = FloatRect(181f, 70f, 192f, 78f)))))
        assertFalse(first.equivalent(next(shifted.copy(image = createDrawImage(image.size, image.copyArgb())))))
        val many = List(500) { small }
        val manyMoved = List(500) { shifted }
        assertFalse(FabricMinecraftPortableImage(many, IntSize(12, 8), 1).equivalent(FabricMinecraftPortableImage(manyMoved, IntSize(12, 8), 1, IntOffset(180, 70))))
    }

    @Test
    fun scaleParticipatesInTheKeyAndPhysicalExtent() {
        val first = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 2)
        val same = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 2)
        val differentScale = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 1)
        val samePhysicalExtent = FabricMinecraftPortableImage(emptyList(), IntSize(6, 4), 1)

        assertEquals(IntSize(6, 4), first.physicalSize)
        assertTrue(first.equivalent(same))
        assertFalse(first.equivalent(differentScale))
        assertFalse(first.equivalent(samePhysicalExtent))
        val shifted = FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 2, IntOffset(180, 60))
        assertFalse(first.equivalent(shifted))
        assertTrue(shifted.equivalent(FabricMinecraftPortableImage(emptyList(), IntSize(3, 2), 2, IntOffset(180, 60))))
    }

    @Test
    fun invalidAndOverflowingPhysicalExtentsFailBeforeNativeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(0, 1), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(1, 1), 0)
        }
        assertThrows(ArithmeticException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(Int.MAX_VALUE, 1), 2)
        }
        assertThrows(ArithmeticException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(2, 1), 2, IntOffset(Int.MAX_VALUE - 2, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FabricMinecraftPortableImage(emptyList(), IntSize(2, 1), 1, IntOffset(-1, 0))
        }
    }
}
