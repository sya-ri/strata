package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame

/**
 * Independent exact-rational pixel oracle with patterned alpha and atlas-neighbor sentinels.
 * No candidate resampling, sampling-axis array or generated-image hash contributes to expected pixels.
 */
internal object PlayerHeadPixelReference {
    /**
     * Canonical base and overlay regions confirmed by the audited Minecraft Java layout tool.
     */
    internal enum class Layer(
        internal val region: IntRect,
    ) {
        Face(IntRect(8, 8, 16, 16)),
        Hat(IntRect(40, 8, 48, 16)),
    }

    /**
     * Builds one immutable source; distinct calls with the same seed have equal pixels and separate identity.
     */
    internal fun skin(seed: Int = 0): DrawImage =
        createDrawImage(IntSize(64, 64)) { x, y ->
            when {
                x in 8 until 16 && y in 8 until 16 -> color(x - 8, y - 8, seed, overlay = false)
                x in 40 until 48 && y in 8 until 16 -> color(x - 40, y - 8, seed, overlay = true)
                else -> 0x7FFF00FF
            }
        }

    /**
     * Checks layer order, original Float bits, tint/cutoff and every straight-ARGB output pixel.
     */
    internal fun verify(
        commands: List<DrawCommand>,
        skin: DrawImage,
        size: Int,
        showHat: Boolean,
    ) {
        val images = commands.filterIsInstance<DrawCommand.SampledImage>()
        assertEquals(if (showHat) 2 else 1, images.size)
        assertEquals(images.size, commands.size)
        val layers = if (showHat) Layer.entries else listOf(Layer.Face)
        images.zip(layers).forEach { (command, layer) ->
            val source =
                if (size % 8 == 0) {
                    val region = layer.region
                    FloatRect(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())
                } else {
                    FloatRect(0f, 0f, size.toFloat(), size.toFloat())
                }
            assertEquals(source, command.source)
            assertEquals(FloatRect(0f, 0f, size.toFloat(), size.toFloat()), command.destination)
            assertEquals(ArgbColor(-1), command.tint)
            assertEquals(0f.toBits(), command.alphaCutoff.toBits())
            if (size % 8 == 0) {
                assertSame(skin, command.image)
            } else {
                assertEquals(IntSize(size, size), command.image.size)
                assertArrayEquals(filtered(skin, size, layer), command.image.copyArgb())
            }
        }
    }

    /**
     * Calculates each center independently using rational coordinates and an explicit weighted channel sum.
     */
    internal fun filtered(
        skin: DrawImage,
        size: Int,
        layer: Layer,
    ): IntArray {
        val region = layer.region
        val source = skin.copyArgb()
        val denominator = size.toLong() * 2
        val total = denominator * denominator
        return IntArray(size * size) { index ->
            val horizontal = ((index % size * 2L + 1) * 8 - size).coerceIn(0L, 7 * denominator)
            val vertical = ((index / size * 2L + 1) * 8 - size).coerceIn(0L, 7 * denominator)
            val left = (horizontal / denominator).toInt()
            val top = (vertical / denominator).toInt()
            val right = (left + 1).coerceAtMost(7)
            val bottom = (top + 1).coerceAtMost(7)
            val column = horizontal % denominator
            val row = vertical % denominator
            val first = source[(region.top + top) * 64 + region.left + left]
            val second = source[(region.top + top) * 64 + region.left + right]
            val third = source[(region.top + bottom) * 64 + region.left + left]
            val fourth = source[(region.top + bottom) * 64 + region.left + right]
            val firstWeight = (denominator - column) * (denominator - row)
            val secondWeight = column * (denominator - row)
            val thirdWeight = (denominator - column) * row
            val fourthWeight = column * row
            val alphaSum =
                (first ushr 24).toLong() * firstWeight +
                    (second ushr 24).toLong() * secondWeight +
                    (third ushr 24).toLong() * thirdWeight +
                    (fourth ushr 24).toLong() * fourthWeight
            val alpha = ((alphaSum + total / 2) / total).toInt()
            if (alpha == 0) {
                0
            } else {
                var argb = alpha shl 24
                for (shift in channels) {
                    val sum =
                        (first ushr shift and 255).toLong() * (first ushr 24) * firstWeight +
                            (second ushr shift and 255).toLong() * (second ushr 24) * secondWeight +
                            (third ushr shift and 255).toLong() * (third ushr 24) * thirdWeight +
                            (fourth ushr shift and 255).toLong() * (fourth ushr 24) * fourthWeight
                    argb = argb or (((sum + alphaSum / 2) / alphaSum).toInt() shl shift)
                }
                argb
            }
        }
    }

    private fun color(
        x: Int,
        y: Int,
        seed: Int,
        overlay: Boolean,
    ): Int {
        val alpha = if (overlay) alphas[(x + y * 3) % alphas.size] else 255
        val red = (x * 29 + y * 11 + seed * 17 + 31) and 255
        val green = (x * 7 + y * 37 + seed * 23 + 83) and 255
        val blue = (x * 19 + y * 13 + seed * 41 + 149) and 255
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private val channels = intArrayOf(16, 8, 0)
    private val alphas = intArrayOf(0, 1, 17, 127, 128, 191, 254, 255)
}
