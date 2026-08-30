package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage

/**
 * Retains the derived images needed to paint one player head without uneven source-texel sizes.
 *
 * Exact integer scales keep the original skin and nearest sampling.
 * Other sizes retain one bilinearly resampled face and hat for the current skin identity and logical size.
 * The owning retained node must call [clear] when its attachment ends.
 */
internal class MinecraftPlayerHeadPainter {
    private var cachedSkin: DrawImage? = null
    private var cachedSize: Int = 0
    private var cachedFace: DrawImage? = null
    private var cachedHat: DrawImage? = null

    /**
     * Paints the base face and optional hat in Minecraft's layer order.
     *
     * @param scope active node-local paint scope.
     * @param skin immutable normalized 64 by 64 skin.
     * @param size positive logical square extent.
     * @param showHat whether the hat layer is painted after the face.
     */
    internal fun paint(
        scope: PaintScope,
        skin: DrawImage,
        size: Int,
        showHat: Boolean,
    ) {
        val destination = FloatRect(0f, 0f, size.toFloat(), size.toFloat())
        if (size % SOURCE_EXTENT == 0) {
            scope.sampledImage(skin, sampledFaceSource, destination, alphaCutoff = 0f)
            if (showHat) {
                scope.sampledImage(skin, sampledHatSource, destination, alphaCutoff = 0f)
            }
            return
        }

        prepare(skin, size)
        val source = FloatRect(0f, 0f, size.toFloat(), size.toFloat())
        scope.sampledImage(checkNotNull(cachedFace), source, destination, alphaCutoff = 0f)
        if (showHat) {
            scope.sampledImage(checkNotNull(cachedHat), source, destination, alphaCutoff = 0f)
        }
    }

    /**
     * Releases references to the source skin and both current derived images.
     */
    internal fun clear() {
        cachedSkin = null
        cachedSize = 0
        cachedFace = null
        cachedHat = null
    }

    private fun prepare(
        skin: DrawImage,
        size: Int,
    ) {
        if (cachedSkin === skin && cachedSize == size) return
        val face = resample(skin, faceSource, size)
        val hat = resample(skin, hatSource, size)
        cachedSkin = skin
        cachedSize = size
        cachedFace = face
        cachedHat = hat
    }

    private fun resample(
        skin: DrawImage,
        region: IntRect,
        size: Int,
    ): DrawImage {
        val area = Math.multiplyExact(size, size)
        val lower = IntArray(size)
        val upper = IntArray(size)
        val fraction = LongArray(size)
        val denominator = size.toLong() * 2
        for (destination in 0 until size) {
            val numerator = (destination.toLong() * 2 + 1) * SOURCE_EXTENT - size
            when {
                numerator <= 0 -> {
                    lower[destination] = 0
                    upper[destination] = 0
                }

                (SOURCE_EXTENT - 1) * denominator <= numerator -> {
                    lower[destination] = SOURCE_EXTENT - 1
                    upper[destination] = SOURCE_EXTENT - 1
                }

                else -> {
                    val sourceLower = (numerator / denominator).toInt()
                    lower[destination] = sourceLower
                    upper[destination] = sourceLower + 1
                    fraction[destination] = numerator % denominator
                }
            }
        }

        val pixels = IntArray(area)
        val samples = IntArray(SAMPLE_COUNT)
        val weights = LongArray(SAMPLE_COUNT)
        val totalWeight = denominator * denominator
        for (y in 0 until size) {
            val sourceTop = region.top + lower[y]
            val sourceBottom = region.top + upper[y]
            val yFraction = fraction[y]
            for (x in 0 until size) {
                val xFraction = fraction[x]
                val leftWeight = denominator - xFraction
                val topWeight = denominator - yFraction
                samples[0] = skin.argbAt(region.left + lower[x], sourceTop)
                samples[1] = skin.argbAt(region.left + upper[x], sourceTop)
                samples[2] = skin.argbAt(region.left + lower[x], sourceBottom)
                samples[3] = skin.argbAt(region.left + upper[x], sourceBottom)
                weights[0] = leftWeight * topWeight
                weights[1] = xFraction * topWeight
                weights[2] = leftWeight * yFraction
                weights[3] = xFraction * yFraction
                pixels[y * size + x] = interpolate(samples, weights, totalWeight)
            }
        }
        return createDrawImage(IntSize(size, size), pixels)
    }

    private fun interpolate(
        samples: IntArray,
        weights: LongArray,
        totalWeight: Long,
    ): Int {
        var alpha = 0L
        for (index in samples.indices) {
            alpha += channel(samples[index], ALPHA_SHIFT) * weights[index]
        }
        val roundedAlpha = roundedDivide(alpha, totalWeight).coerceIn(CHANNEL_MIN, CHANNEL_MAX)
        if (roundedAlpha == 0) return 0
        val red = premultipliedChannel(samples, weights, alpha, RED_SHIFT)
        val green = premultipliedChannel(samples, weights, alpha, GREEN_SHIFT)
        val blue = premultipliedChannel(samples, weights, alpha, BLUE_SHIFT)
        return (roundedAlpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    private fun premultipliedChannel(
        samples: IntArray,
        weights: LongArray,
        alpha: Long,
        shift: Int,
    ): Int {
        var premultiplied = 0L
        for (index in samples.indices) {
            premultiplied += channel(samples[index], shift) * channel(samples[index], ALPHA_SHIFT) * weights[index]
        }
        return roundedDivide(premultiplied, alpha).coerceIn(CHANNEL_MIN, CHANNEL_MAX)
    }

    private fun roundedDivide(
        numerator: Long,
        denominator: Long,
    ): Int = ((numerator + denominator / 2) / denominator).toInt()

    private fun channel(
        argb: Int,
        shift: Int,
    ): Int = argb ushr shift and CHANNEL_MAX

    /**
     * Owns player-skin geometry, cache limits, and validation shared by synchronous and asynchronous retained nodes.
     */
    companion object {
        /**
         * Validates one logical head extent against the bounded derived-image cache.
         *
         * @param size requested positive logical square extent.
         * @throws IllegalArgumentException when [size] is not positive or a filtered size exceeds the 1,024 by 1,024 per-layer cache bound.
         */
        @JvmSynthetic
        internal fun validateSize(size: Int) {
            require(0 < size) { "PlayerHead size must be positive." }
            require(size % SOURCE_EXTENT == 0 || size <= MAX_FILTERED_EXTENT) {
                "A PlayerHead size not divisible by eight must not exceed $MAX_FILTERED_EXTENT."
            }
        }

        private const val SOURCE_EXTENT: Int = 8
        private const val MAX_FILTERED_EXTENT: Int = 1024
        private const val SAMPLE_COUNT: Int = 4
        private const val CHANNEL_MIN: Int = 0
        private const val CHANNEL_MAX: Int = 0xFF
        private const val ALPHA_SHIFT: Int = 24
        private const val RED_SHIFT: Int = 16
        private const val GREEN_SHIFT: Int = 8
        private const val BLUE_SHIFT: Int = 0
        private val faceSource = IntRect(8, 8, 16, 16)
        private val hatSource = IntRect(40, 8, 48, 16)
        private val sampledFaceSource = FloatRect(8f, 8f, 16f, 16f)
        private val sampledHatSource = FloatRect(40f, 8f, 48f, 16f)
    }
}
