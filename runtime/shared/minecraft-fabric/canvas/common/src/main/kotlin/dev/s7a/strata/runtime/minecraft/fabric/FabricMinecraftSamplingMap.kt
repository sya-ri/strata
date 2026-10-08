@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Supplies exact headless texel indices for one tight GPU-resampled image without reading source pixels.
 *
 * The first two rows encode x and y indices plus one; zero denotes an uncovered physical pixel.
 * Row two stores output width and height plus one, followed by an opaque RGB mask and exact alpha-byte threshold.
 * RGBA bytes encode little-endian integers, independently of the source image's alpha or colors.
 * Each axis is bounded to 4,096 pixels. Only the current prepared frame retains these immutable CPU inputs.
 */
internal class FabricMinecraftSamplingMap(
    @get:JvmSynthetic internal val command: DrawCommand.SampledImage,
    @get:JvmSynthetic internal val bounds: IntRect,
    private val scale: Int,
) {
    /**
     * Exact physical offscreen output extent; its pixels are generated on the GPU.
     */
    @get:JvmSynthetic
    internal val physicalSize: IntSize = IntSize(Math.multiplyExact(bounds.width, scale), Math.multiplyExact(bounds.height, scale))

    /**
     * Immutable axis metadata, at most 4,096 by three RGBA texels, generated without rasterizing the image.
     */
    @get:JvmSynthetic
    internal val indices: DrawImage

    init {
        require(physicalSize.width in 1..4_096 && physicalSize.height in 1..4_096) { "Exact sampled GPU axes must fit the bounded lookup." }
        require(command.hasExactFabricSamplingEffects()) { "Exact sampled GPU effects must preserve identity sampling or select only opaque channel-masked texels." }
        val width = maxOf(3, physicalSize.width, physicalSize.height)
        val left = Math.multiplyExact(bounds.left, scale)
        val top = Math.multiplyExact(bounds.top, scale)
        val source = command.source
        val destination = command.destination
        val minimumAlpha = if (command.alphaCutoff == 1f) 255 else 0
        val mask = (command.tint.value ushr 16 and 1) or ((command.tint.value ushr 8 and 1) shl 1) or ((command.tint.value and 1) shl 2)
        val effects = (minimumAlpha shl 3) or mask
        indices =
            createDrawImage(IntSize(width, 3)) { coordinate, row ->
                val value =
                    when (row) {
                        0 -> {
                            if (coordinate < physicalSize.width) axis(Math.addExact(left, coordinate), destination.left, destination.right, if (command.orientation.flipX) source.right else source.left, if (command.orientation.flipX) source.left else source.right, command.image.size.width) else 0
                        }

                        1 -> {
                            if (coordinate < physicalSize.height) axis(Math.addExact(top, coordinate), destination.top, destination.bottom, if (command.orientation.flipY) source.bottom else source.top, if (command.orientation.flipY) source.top else source.bottom, command.image.size.height) else 0
                        }

                        2 -> {
                            when (coordinate) {
                                0 -> physicalSize.width + 1
                                1 -> physicalSize.height + 1
                                2 -> effects
                                else -> 0
                            }
                        }

                        else -> {
                            0
                        }
                    }
                encode(value)
            }
    }

    /**
     * Proves equal GPU output from the same immutable source and identical axis selections, independent of placement.
     * Reads only bounded current-frame index metadata; no source pixels, hashing, history, or temporary array is needed.
     */
    @JvmSynthetic
    internal fun equivalent(other: FabricMinecraftSamplingMap): Boolean {
        if (physicalSize != other.physicalSize || command.image !== other.command.image) return false
        if (command.tint != other.command.tint || command.alphaCutoff != other.command.alphaCutoff) return false
        for (x in 0 until physicalSize.width) if (indices.argbAt(x, 0) != other.indices.argbAt(x, 0)) return false
        for (y in 0 until physicalSize.height) if (indices.argbAt(y, 1) != other.indices.argbAt(y, 1)) return false
        return true
    }

    private fun axis(
        physical: Int,
        destinationStart: Float,
        destinationEnd: Float,
        sourceStart: Float,
        sourceEnd: Float,
        sourceSize: Int,
    ): Int {
        val first = ceil(destinationStart.toDouble() * scale - 0.5)
        val last = ceil(destinationEnd.toDouble() * scale - 0.5)
        if ((physical.toDouble() in first..<last).not()) return 0
        val center = (physical.toFloat() + 0.5f) / scale.toFloat()
        val relative = (center - destinationStart) / (destinationEnd - destinationStart)
        val sample = sourceStart * (1f - relative) + sourceEnd * relative
        return floor(sample).toInt().coerceIn(0, sourceSize - 1) + 1
    }

    // Encode R, G, B, A rather than relying on a native byte order or signed color arithmetic.
    private fun encode(value: Int): Int = ((value and 255) shl 16) or (((value ushr 8) and 255) shl 8) or ((value ushr 16) and 255) or ((value ushr 24) shl 24)
}
