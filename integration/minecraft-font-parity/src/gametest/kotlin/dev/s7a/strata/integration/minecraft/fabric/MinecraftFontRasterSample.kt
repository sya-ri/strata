package dev.s7a.strata.integration.minecraft.fabric

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One independently evaluated opaque-background fragment history for the parity fixture.
 * State is detached and immutable; native pixels never participate in its construction.
 * Separate channel error bounds propagate the local ULP of each shader and blend operation; byte error propagates one RGBA8 unit per effective blend.
 */
internal data class MinecraftFontRasterSample(
    val red: Float,
    val green: Float,
    val blue: Float,
    val argb: Int,
    val redFloatError: Double = 0.0,
    val greenFloatError: Double = 0.0,
    val blueFloatError: Double = 0.0,
    val redByteError: Double = 0.0,
    val greenByteError: Double = 0.0,
    val blueByteError: Double = 0.0,
    val boundary: Boolean = false,
) {
    /**
     * Evaluates one ordinary text shader sample with source-alpha blending onto an opaque destination.
     * Discarded or fully transparent samples leave the previous history unchanged.
     */
    fun blend(
        source: Int,
        tint: Int,
        cutoff: Float,
        rasterBoundary: Boolean,
    ): MinecraftFontRasterSample {
        val alpha = shaderComponent(source ushr 24) * shaderComponent(tint ushr 24)
        if (alpha.value < cutoff || alpha.value == 0f) return if (rasterBoundary) copy(boundary = true) else this
        val remaining = FloatValue(1f) - alpha
        val sourceRed = shaderComponent(source ushr 16) * shaderComponent(tint ushr 16)
        val sourceGreen = shaderComponent(source ushr 8) * shaderComponent(tint ushr 8)
        val sourceBlue = shaderComponent(source) * shaderComponent(tint)
        val nextRed = sourceRed * alpha + FloatValue(red, redFloatError) * remaining
        val nextGreen = sourceGreen * alpha + FloatValue(green, greenFloatError) * remaining
        val nextBlue = sourceBlue * alpha + FloatValue(blue, blueFloatError) * remaining
        val byteRed = quantize(sourceRed.value * alpha.value + normalized(argb ushr 16) * remaining.value)
        val byteGreen = quantize(sourceGreen.value * alpha.value + normalized(argb ushr 8) * remaining.value)
        val byteBlue = quantize(sourceBlue.value * alpha.value + normalized(argb) * remaining.value)
        return MinecraftFontRasterSample(
            nextRed.value,
            nextGreen.value,
            nextBlue.value,
            (0xFF shl 24) or (byteRed shl 16) or (byteGreen shl 8) or byteBlue,
            nextRed.error,
            nextGreen.error,
            nextBlue.error,
            byteError(sourceRed.value, red, redByteError, alpha.value),
            byteError(sourceGreen.value, green, greenByteError, alpha.value),
            byteError(sourceBlue.value, blue, blueByteError, alpha.value),
            (boundary && alpha.value != 1f) || rasterBoundary,
        )
    }

    /**
     * Requires native float output to agree with the independently evaluated shader/blend arithmetic.
     */
    fun contains(sample: MinecraftFontFloatImage.Sample): Boolean =
        sample.alpha == 1f && abs(sample.red.toDouble() - red) <= redFloatError &&
            abs(sample.green.toDouble() - green) <= greenFloatError && abs(sample.blue.toDouble() - blue) <= blueFloatError

    /**
     * Bounds final native byte conversion separately from float shader/geometry agreement.
     */
    fun contains(nativeArgb: Int): Boolean =
        nativeArgb ushr 24 == 0xFF &&
            difference(nativeArgb ushr 16, argb ushr 16) <= redByteError &&
            difference(nativeArgb ushr 8, argb ushr 8) <= greenByteError &&
            difference(nativeArgb, argb) <= blueByteError

    private fun byteError(
        source: Float,
        destination: Float,
        previous: Double,
        alpha: Float,
    ): Double {
        val endpoint = source == 0f || source == 1f
        val preserved = source == destination && previous == 0.0
        if (endpoint && (alpha == 1f || preserved)) return 0.0
        return previous * (1f - alpha) + 1.0
    }

    private fun difference(
        first: Int,
        second: Int,
    ): Double = abs((first and 0xFF) - (second and 0xFF)).toDouble()

    private data class FloatValue(
        val value: Float,
        val error: Double = 0.0,
    ) {
        private val exactZero: Boolean get() = value == 0f && error == 0.0

        private val exactOne: Boolean get() = value == 1f && error == 0.0

        operator fun times(other: FloatValue): FloatValue {
            if (exactZero) return this
            if (other.exactZero) return other
            if (exactOne) return other
            if (other.exactOne) return this
            val result = value * other.value
            // Expand (x + dx)(y + dy); each native rounded operation receives one ULP at its own result magnitude.
            val propagated = abs(value.toDouble()) * other.error + abs(other.value.toDouble()) * error + error * other.error
            return FloatValue(result, propagated + Math.ulp(result).toDouble())
        }

        operator fun plus(other: FloatValue): FloatValue {
            if (exactZero) return other
            if (other.exactZero) return this
            val result = value + other.value
            return FloatValue(result, error + other.error + Math.ulp(result).toDouble())
        }

        operator fun minus(other: FloatValue): FloatValue {
            if (other.exactZero) return this
            // Equal rounded centers do not cancel independent uncertainty; both operands must already be exact.
            if (error == 0.0 && other.error == 0.0 && value == other.value) return FloatValue(0f)
            val result = value - other.value
            return FloatValue(result, error + other.error + Math.ulp(result).toDouble())
        }
    }

    /**
     * Creates exact initial opaque color state without a preceding text-fragment quantization allowance.
     */
    companion object {
        /**
         * Rejects transparent backgrounds because this oracle deliberately isolates ordinary opaque GUI composition.
         */
        fun background(argb: Int): MinecraftFontRasterSample {
            require(argb ushr 24 == 0xFF)
            val red = shaderComponent(argb ushr 16)
            val green = shaderComponent(argb ushr 8)
            val blue = shaderComponent(argb)
            return MinecraftFontRasterSample(red.value, green.value, blue.value, argb, red.error, green.error, blue.error)
        }

        private fun shaderComponent(value: Int): FloatValue {
            val normalized = normalized(value)
            val error = if (normalized == 0f || normalized == 1f) 0.0 else Math.ulp(normalized).toDouble()
            return FloatValue(normalized, error)
        }

        private fun normalized(value: Int): Float = (value and 0xFF).toFloat() / 255f

        private fun quantize(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
    }
}
