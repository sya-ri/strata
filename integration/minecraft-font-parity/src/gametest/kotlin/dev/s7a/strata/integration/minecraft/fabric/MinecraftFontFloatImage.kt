package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Detached native RGBA32F evidence, never used as a resource or pixel input to the portable renderer.
 * Instances copy their input and are immutable and safe to share between comparison threads.
 * The evidence must contain finite normalized channels and exactly the expected physical dimensions.
 */
internal class MinecraftFontFloatImage private constructor(
    val size: IntSize,
    private val rgba: FloatArray,
) {
    /**
     * Returns one detached RGBA sample; invalid physical coordinates fail immediately.
     */
    fun sample(
        x: Int,
        y: Int,
    ): Sample {
        require(0 <= x && x < size.width && 0 <= y && y < size.height)
        val offset = (y * size.width + x) * 4
        return Sample(rgba[offset], rgba[offset + 1], rgba[offset + 2], rgba[offset + 3])
    }

    /**
     * Immutable normalized components observed at one physical pixel in the native float target.
     */
    data class Sample(
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
    )

    /**
     * Strict readers and synthetic evidence construction used by the comparison regression tests.
     */
    companion object {
        /**
         * Reads big-endian width, height, then top-down RGBA floats from the current native run.
         * Missing, linked, truncated, oversized, non-finite, and dimension-mismatched evidence fails.
         * The stream is closed before returning and the file is never retained by the result.
         */
        fun read(
            path: Path,
            expectedSize: IntSize,
        ): MinecraftFontFloatImage {
            val channels = Math.multiplyExact(Math.multiplyExact(expectedSize.width, expectedSize.height), 4)
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Required native float evidence is missing: $path" }
            check(Files.size(path) == 8L + channels.toLong() * Float.SIZE_BYTES) { "Invalid native float evidence length: $path" }
            return DataInputStream(Files.newInputStream(path).buffered()).use { input ->
                check(input.readInt() == expectedSize.width && input.readInt() == expectedSize.height) { "Native float evidence dimensions differ: $path" }
                of(expectedSize, FloatArray(channels) { input.readFloat() })
            }
        }

        /**
         * Copies and validates synthetic normalized evidence without retaining caller-owned mutable storage.
         */
        fun of(
            size: IntSize,
            rgba: FloatArray,
        ): MinecraftFontFloatImage {
            require(0 < size.width && 0 < size.height)
            require(rgba.size == Math.multiplyExact(Math.multiplyExact(size.width, size.height), 4))
            require(rgba.all { it.isFinite() && 0f <= it && it <= 1f }) { "Native float evidence must contain finite normalized channels." }
            return MinecraftFontFloatImage(size, rgba.copyOf())
        }
    }
}
