package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor

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
    @get:JvmSynthetic
    internal val sampling: FabricMinecraftSamplingMap? = null,
) {
    /**
     * Exact positive physical upload and lifetime-reservation extent derived with checked arithmetic.
     */
    @get:JvmSynthetic
    internal val physicalSize: IntSize

    /**
     * Conservative allocation rectangle covering both GPU output and its three-row axis texture under one owner.
     */
    @get:JvmSynthetic
    internal val reservationSize: IntSize
        get() = sampling?.let { IntSize(maxOf(physicalSize.width, it.indices.size.width), Math.addExact(physicalSize.height, 3)) } ?: physicalSize

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
     * Exact GPU outputs may move when their bounded axis metadata proves identical source texels and coverage.
     * Moved CPU runs compare ordered coverage and exact original-coordinate samples under an 8,192-index proof budget.
     * Rasterization always retains the original sampling origin; no Float translation is assumed equivalent.
     */
    @JvmSynthetic
    internal fun equivalent(other: FabricMinecraftPortableImage): Boolean {
        val indices = sampling
        if (indices != null) return size == other.size && scale == other.scale && other.sampling?.let(indices::equivalent) == true
        val sameGeometry = origin == other.origin && size == other.size && scale == other.scale
        if (sameGeometry && commands == other.commands && other.sampling == null) return true
        if (size != other.size || scale != other.scale || other.sampling != null) return false
        return translated(other)
    }

    private fun translated(other: FabricMinecraftPortableImage): Boolean {
        if (commands.isEmpty() || commands.size != other.commands.size) return false
        var remaining = 8_192
        for (index in commands.indices) {
            val command = commands[index]
            if (command is DrawCommand.SampledImage && (command.image.size.width != 1 || command.image.size.height != 1)) {
                if (4_096 < physicalSize.width || 4_096 < physicalSize.height) return false
                val bounds = command.destination
                val width = edge(bounds.right, origin.x, physicalSize.width) - edge(bounds.left, origin.x, physicalSize.width)
                val height = edge(bounds.bottom, origin.y, physicalSize.height) - edge(bounds.top, origin.y, physicalSize.height)
                val cost = width + height
                if (remaining < cost) return false
                remaining -= cost
            }
            if (sameCommand(command, other.commands[index], other).not()) return false
        }
        return true
    }

    // The exhaustive ordered visitor proves each portable primitive without constructing translated commands or pixels.
    @Suppress("CyclomaticComplexMethod")
    private fun sameCommand(
        a: DrawCommand,
        b: DrawCommand,
        other: FabricMinecraftPortableImage,
    ): Boolean =
        when (a) {
            is DrawCommand.FillRectangle -> {
                b is DrawCommand.FillRectangle && a.color == b.color && sameRectangle(a.bounds, b.bounds, other, clipped = true)
            }

            is DrawCommand.BlitImage -> {
                if (b is DrawCommand.BlitImage) {
                    val sameSource = a.image === b.image && a.source == b.source
                    sameSource && sameRectangle(a.destination, b.destination, other)
                } else {
                    false
                }
            }

            is DrawCommand.BlitImagePixels -> {
                if (b is DrawCommand.BlitImagePixels) {
                    val sameSource = a.image === b.image && a.source == b.source
                    sameSource && sameRectangle(a.destination, b.destination, other)
                } else {
                    false
                }
            }

            is DrawCommand.SampledImage -> {
                b is DrawCommand.SampledImage && sameSamples(a, b, other)
            }

            is DrawCommand.PushClip -> {
                b is DrawCommand.PushClip && sameRectangle(a.bounds, b.bounds, other, clipped = true)
            }

            is DrawCommand.PushFractionalClip -> {
                b is DrawCommand.PushFractionalClip && sameCoverage(a.bounds, b.bounds, other)
            }

            DrawCommand.PopClip -> {
                b == DrawCommand.PopClip
            }

            is DrawCommand.Platform -> {
                false
            }
        }

    private fun sameRectangle(
        a: IntRect,
        b: IntRect,
        other: FabricMinecraftPortableImage,
        clipped: Boolean = false,
    ): Boolean {
        val minimum = if (clipped) 0L else Long.MIN_VALUE
        val maximumX = if (clipped) size.width.toLong() else Long.MAX_VALUE
        val maximumY = if (clipped) size.height.toLong() else Long.MAX_VALUE
        val horizontal =
            (a.left.toLong() - origin.x).coerceIn(minimum, maximumX) == (b.left.toLong() - other.origin.x).coerceIn(minimum, maximumX) &&
                (a.right.toLong() - origin.x).coerceIn(minimum, maximumX) == (b.right.toLong() - other.origin.x).coerceIn(minimum, maximumX)
        val vertical =
            (a.top.toLong() - origin.y).coerceIn(minimum, maximumY) == (b.top.toLong() - other.origin.y).coerceIn(minimum, maximumY) &&
                (a.bottom.toLong() - origin.y).coerceIn(minimum, maximumY) == (b.bottom.toLong() - other.origin.y).coerceIn(minimum, maximumY)
        return horizontal && vertical
    }

    private fun sameCoverage(
        a: FloatRect,
        b: FloatRect,
        other: FabricMinecraftPortableImage,
    ): Boolean {
        val horizontal = edge(a.left, origin.x, physicalSize.width) == edge(b.left, other.origin.x, physicalSize.width) && edge(a.right, origin.x, physicalSize.width) == edge(b.right, other.origin.x, physicalSize.width)
        val vertical = edge(a.top, origin.y, physicalSize.height) == edge(b.top, other.origin.y, physicalSize.height) && edge(a.bottom, origin.y, physicalSize.height) == edge(b.bottom, other.origin.y, physicalSize.height)
        return horizontal && vertical
    }

    private fun edge(
        value: Float,
        offset: Int,
        extent: Int,
    ): Int = (ceil(value.toDouble() * scale - 0.5) - offset.toDouble() * scale).coerceIn(0.0, extent.toDouble()).toInt()

    private fun sameSamples(
        a: DrawCommand.SampledImage,
        b: DrawCommand.SampledImage,
        other: FabricMinecraftPortableImage,
    ): Boolean {
        if (a.image !== b.image || a.source != b.source || a.orientation != b.orientation) return false
        if (a.tint != b.tint || a.alphaCutoff != b.alphaCutoff) return false
        if (sameCoverage(a.destination, b.destination, other).not()) return false
        if (a.image.size.width == 1 && a.image.size.height == 1) return true
        val source = a.source
        val xStart = if (a.orientation.flipX) source.right else source.left
        val xEnd = if (a.orientation.flipX) source.left else source.right
        val yStart = if (a.orientation.flipY) source.bottom else source.top
        val yEnd = if (a.orientation.flipY) source.top else source.bottom
        return sameAxis(a.destination.left, a.destination.right, b.destination.left, b.destination.right, origin.x, other.origin.x, physicalSize.width, xStart, xEnd, a.image.size.width) &&
            sameAxis(a.destination.top, a.destination.bottom, b.destination.top, b.destination.bottom, origin.y, other.origin.y, physicalSize.height, yStart, yEnd, a.image.size.height)
    }

    private fun sameAxis(
        aStart: Float,
        aEnd: Float,
        bStart: Float,
        bEnd: Float,
        aOffset: Int,
        bOffset: Int,
        extent: Int,
        sourceStart: Float,
        sourceEnd: Float,
        sourceSize: Int,
    ): Boolean {
        for (position in edge(aStart, aOffset, extent) until edge(aEnd, aOffset, extent)) {
            val a = sample(aOffset * scale + position, aStart, aEnd, sourceStart, sourceEnd, sourceSize)
            val b = sample(bOffset * scale + position, bStart, bEnd, sourceStart, sourceEnd, sourceSize)
            if (a != b) return false
        }
        return true
    }

    private fun sample(
        physical: Int,
        start: Float,
        end: Float,
        sourceStart: Float,
        sourceEnd: Float,
        sourceSize: Int,
    ): Int {
        val center = (physical.toFloat() + 0.5f) / scale.toFloat()
        val relative = (center - start) / (end - start)
        return floor(sourceStart * (1f - relative) + sourceEnd * relative).toInt().coerceIn(0, sourceSize - 1)
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
