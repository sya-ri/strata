package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow

/**
 * Measured precision limits from the same native scene and process as the float and RGBA8 captures.
 * These immutable values bound candidate raster ambiguities; they never contain expected screen pixels.
 * Unsupported or implausible evidence fails rather than enlarging a comparison tolerance.
 *
 * @property subpixelBits actual GL_SUBPIXEL_BITS queried from the rendering device.
 * @property atlasExtent maximum actual native font atlas dimension observed while drawing this scene.
 */
internal data class MinecraftFontGpuPrecision(
    val subpixelBits: Int,
    val atlasExtent: Int,
) {
    init {
        require(subpixelBits in 4..24) { "Unsupported native subpixel precision." }
        require(atlasExtent in 1..16384) { "Unsupported native font atlas extent." }
    }

    /**
     * One measured device subpixel grid unit in physical pixel coordinates.
     */
    val subpixelUnit: Double = 2.0.pow(-subpixelBits)

    /**
     * Eight rounded float operations bound atlas-normalization and interpolation error in source texels.
     * This is an arithmetic bound at the measured atlas extent, not a configurable image tolerance.
     */
    val texelRounding: Double = Math.ulp(atlasExtent.toFloat()).toDouble() * 8.0

    /**
     * Loads only explicitly recorded device/atlas observations from the current native float capture.
     */
    companion object {
        /**
         * Rejects missing or malformed native precision evidence; no guessed device defaults are used.
         */
        fun read(
            path: Path,
            scale: Int,
            size: IntSize,
        ): MinecraftFontGpuPrecision {
            val values = linkedMapOf<String, String>()
            Files.readAllLines(path).filter { it.isNotBlank() }.forEach { line ->
                val separator = line.indexOf('=')
                check(0 < separator) { "Malformed native precision evidence: $path" }
                check(values.put(line.substring(0, separator), line.substring(separator + 1)) == null) { "Duplicate native precision key: $path" }
            }
            check(checkNotNull(values["scale"]).toInt() == scale) { "Native precision evidence belongs to another GUI scale." }
            check(checkNotNull(values["width"]).toInt() == size.width && checkNotNull(values["height"]).toInt() == size.height) { "Native precision evidence belongs to another physical viewport." }
            check(FloatFormat.valueOf(checkNotNull(values["colorFormat"])) == FloatFormat.RGBA32F) { "Native precision evidence does not describe the float target." }
            check(checkNotNull(values["preparationThreads"]).toInt() == 1) { "Native precision evidence requires serialized standard provider preparation." }
            val width = checkNotNull(values["maxAtlasWidth"]).toInt()
            val height = checkNotNull(values["maxAtlasHeight"]).toInt()
            check(width in 1..16384 && height in 1..16384) { "Native font atlas observations must both be positive and bounded." }
            return MinecraftFontGpuPrecision(
                checkNotNull(values["subpixelBits"]).toInt(),
                maxOf(width, height),
            )
        }
    }

    private enum class FloatFormat { RGBA32F }
}
