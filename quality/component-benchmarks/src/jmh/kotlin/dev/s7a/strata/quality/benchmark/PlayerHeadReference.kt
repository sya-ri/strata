package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Independent exact-rational alpha oracle for the fixed public-host fixture, outside every timed operation.
 * It reads a detached source array and never calls candidate resampling or derives expected pixels from output hashes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object PlayerHeadReference {
    /**
     * Same-asset immutable inputs with separate identity and patterned edge and alpha controls.
     */
    internal fun skin(seed: Int): DrawImage =
        createDrawImage(IntSize(64, 64)) { x, y ->
            val face = x in 8 until 16 && y in 8 until 16
            val hat = x in 40 until 48 && y in 8 until 16
            if (face || hat) {
                val column = if (face) x - 8 else x - 40
                val row = y - 8
                val alpha = if (face) 255 else alphaValues[(column + row * 3) % alphaValues.size]
                (alpha shl 24) or
                    (((column * 29 + row * 11 + seed * 17 + 31) and 255) shl 16) or
                    (((column * 7 + row * 37 + seed * 23 + 83) and 255) shl 8) or
                    ((column * 19 + row * 13 + seed * 41 + 149) and 255)
            } else {
                0x7FFF00FF
            }
        }

    /**
     * Requires exact ordered commands, Float bits, straight ARGB and source identity for every head.
     */
    internal fun verify(
        frame: RuntimeUiFrame,
        skin: DrawImage,
        size: Int,
        count: Int,
        visible: Boolean,
    ) {
        val layers = if (visible) Layer.entries else listOf(Layer.Face)
        check(frame.drawCommands.size == count * layers.size)
        check(frame.semantics.isEmpty())
        val expected = layers.associateWith { if (size % 8 == 0) null else filtered(skin, size, it.region) }
        frame.drawCommands.forEachIndexed { index, draw ->
            val command = draw as DrawCommand.SampledImage
            val layer = layers[index % layers.size]
            val left = index / layers.size * size
            val rectangle = if (size % 8 == 0) layer.region.toFloatRect() else FloatRect(0f, 0f, size.toFloat(), size.toFloat())
            check(bits(command.source) == bits(rectangle))
            check(bits(command.destination) == bits(FloatRect(left.toFloat(), 0f, (left + size).toFloat(), size.toFloat())))
            check(command.tint == ArgbColor(-1))
            check(command.alphaCutoff.toBits() == 0f.toBits())
            check(command.orientation == SampledImageOrientation.Normal)
            val pixels = expected[layer]
            if (pixels == null) {
                check(command.image === skin)
            } else {
                check(command.image.size == IntSize(size, size))
                check(command.image.copyArgb().contentEquals(pixels)) { "Independent PlayerHead alpha/pixel parity failed." }
            }
        }
    }

    /**
     * Computes each center directly without implementation sampling-axis tables or intermediate image factories.
     */
    internal fun filtered(
        skin: DrawImage,
        size: Int,
        region: IntRect,
    ): IntArray {
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

    private fun IntRect.toFloatRect(): FloatRect = FloatRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun bits(rectangle: FloatRect): List<Int> = listOf(rectangle.left.toBits(), rectangle.top.toBits(), rectangle.right.toBits(), rectangle.bottom.toBits())

    private enum class Layer(
        val region: IntRect,
    ) {
        Face(IntRect(8, 8, 16, 16)),
        Hat(IntRect(40, 8, 48, 16)),
    }

    private val alphaValues = intArrayOf(0, 1, 17, 127, 128, 191, 254, 255)
    private val channels = intArrayOf(16, 8, 0)
}
