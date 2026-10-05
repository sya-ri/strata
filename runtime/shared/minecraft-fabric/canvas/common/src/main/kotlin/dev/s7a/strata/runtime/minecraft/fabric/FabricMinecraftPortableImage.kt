package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Describes one immutable portable command run within a positive image extent and its sampling origin.
 *
 * The presenter constructs these descriptions on the render thread and never modifies their command lists.
 * They own no native resource and retain only the current prepared frame's immutable CPU drawing inputs.
 * Integer-only localized runs omit placement; sampled runs retain absolute coordinates to preserve Float pixel selection.
 *
 * @param commands immutable portable commands in the selected sampling coordinates, including balanced clips.
 * @param size exact positive raster extent in logical pixels.
 * @param scale positive logical-to-physical GUI scale included in the derived-pixel cache key.
 * @param origin nonnegative original sampling origin; zero preserves ordinary localized rasterization.
 * @throws IllegalArgumentException when [size] or [scale] is not positive.
 * @throws ArithmeticException when either checked physical dimension exceeds [Int.MAX_VALUE].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPortableImage(
    @get:JvmSynthetic
    internal val commands: List<DrawCommand>,
    @get:JvmSynthetic
    internal val size: IntSize,
    @get:JvmSynthetic
    internal val scale: Int,
    @get:JvmSynthetic
    internal val origin: IntOffset = IntOffset.Zero,
) {
    /**
     * Exact positive physical upload and lifetime-reservation extent derived with checked arithmetic.
     */
    @get:JvmSynthetic
    internal val physicalSize: IntSize

    init {
        require(0 < size.width && 0 < size.height) { "Portable image size must be positive." }
        require(0 < scale) { "Portable image scale must be positive." }
        require(0 <= origin.x && 0 <= origin.y) { "Portable sampling origin must be nonnegative." }
        physicalSize = IntSize(Math.multiplyExact(size.width, scale), Math.multiplyExact(size.height, scale))
        Math.multiplyExact(Math.addExact(origin.x, size.width), scale)
        Math.multiplyExact(Math.addExact(origin.y, size.height), scale)
    }

    /**
     * Compares pixel inputs before allocating a replacement portable generation; performs no device work or allocation.
     */
    @JvmSynthetic
    internal fun equivalent(other: FabricMinecraftPortableImage): Boolean {
        if (origin != other.origin) return false
        return size == other.size && scale == other.scale && commands == other.commands
    }

    /**
     * Creates only this run's physical storage while preserving original sampled-coordinate arithmetic.
     */
    @JvmSynthetic
    internal fun rasterize(): HeadlessImage =
        if (origin == IntOffset.Zero) {
            rasterizeHeadless(commands, size, scale)
        } else {
            rasterizeHeadlessRegion(commands, IntRect(origin.x, origin.y, Math.addExact(origin.x, size.width), Math.addExact(origin.y, size.height)), scale)
        }
}
