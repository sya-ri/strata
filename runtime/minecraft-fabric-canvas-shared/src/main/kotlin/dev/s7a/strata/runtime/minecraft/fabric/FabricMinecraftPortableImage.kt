package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand

/**
 * Describes one immutable portable command run in coordinates local to its positive image extent.
 *
 * The presenter constructs these descriptions on the render thread and never modifies their command lists.
 * They own no native resource and retain only the current prepared frame's immutable CPU drawing inputs.
 * Placement is deliberately absent: moving an otherwise identical image does not require another upload.
 *
 * @param commands immutable localized portable commands, including balanced clips.
 * @param size exact positive raster extent in logical pixels.
 * @param scale positive logical-to-physical GUI scale included in the derived-pixel cache key.
 * @throws IllegalArgumentException when [size] or [scale] is not positive.
 * @throws ArithmeticException when either checked physical dimension exceeds [Int.MAX_VALUE].
 */
internal class FabricMinecraftPortableImage(
    @get:JvmSynthetic
    internal val commands: List<DrawCommand>,
    @get:JvmSynthetic
    internal val size: IntSize,
    @get:JvmSynthetic
    internal val scale: Int,
) {
    /**
     * Exact positive physical upload and lifetime-reservation extent derived with checked arithmetic.
     */
    @get:JvmSynthetic
    internal val physicalSize: IntSize

    init {
        require(0 < size.width && 0 < size.height) { "Portable image size must be positive." }
        require(0 < scale) { "Portable image scale must be positive." }
        physicalSize = IntSize(Math.multiplyExact(size.width, scale), Math.multiplyExact(size.height, scale))
    }

    /**
     * Compares derived pixel inputs without consulting a device, allocating storage, or retaining [other].
     *
     * The render owner uses this pure comparison before deciding whether to allocate a whole portable generation.
     */
    @JvmSynthetic
    internal fun equivalent(other: FabricMinecraftPortableImage): Boolean = size == other.size && scale == other.scale && commands == other.commands
}
