@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createOwnedDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Exact original-coordinate inputs for one ordered portable tile's ping-pong GPU composition.
 * Three RGBA rows per pass contain physical x/y coverage and source indices, then typed command parameters.
 * One 1536-texel factor row per tint contains CPU binary32 normalization, alpha, inverse alpha and RGB tint products.
 * These immutable descriptions belong only to the current prepared frame and own no native resource or destination pixels.
 * Admission reserves both outputs and all three CPU/staging/GPU metadata payload copies before constructing image arrays.
 */
internal class FabricMinecraftCompositionMap private constructor(
    @get:JvmSynthetic internal val physicalSize: IntSize,
    @get:JvmSynthetic internal val sources: List<DrawImage?>,
    @get:JvmSynthetic internal val indices: DrawImage,
    @get:JvmSynthetic internal val factors: DrawImage,
    @get:JvmSynthetic internal val orderedTints: List<Int>,
    @get:JvmSynthetic internal val axisEntriesWritten: Long,
) {
    /**
     * Complete CPU-to-GPU metadata payload, excluding generated destinations and independently cached sources.
     */
    @get:JvmSynthetic
    internal val uploadBytes: Long = (indices.size.width.toLong() * indices.size.height + factors.size.width.toLong() * factors.size.height) * 4L

    /**
     * Conservative owner reservation covering both RGBA8 destinations and all metadata payload copies.
     */
    @get:JvmSynthetic
    internal val reservationSize: IntSize
        get() {
            val width = maxOf(physicalSize.width, indices.size.width, factors.size.width)
            val bytes = FabricMinecraftSamplingBudget.compositionBytes(physicalSize, uploadBytes / 4L, sources.size)
            return IntSize(width, Math.toIntExact((bytes + width * 4L - 1) / (width * 4L)))
        }

    /**
     * Reserves one immutable output plus unchanged CPU/staging/GPU metadata and per-tile overhead.
     * The separately reserved generation workspace supplies the other alternating destination.
     */
    @get:JvmSynthetic
    internal val singleOutputReservationSize: IntSize
        get() {
            val width = maxOf(physicalSize.width, indices.size.width, factors.size.width)
            val bytes = FabricMinecraftSamplingBudget.compositionBytes(physicalSize, uploadBytes / 4L, sources.size)
            val output = Math.multiplyExact(Math.multiplyExact(physicalSize.width.toLong(), physicalSize.height.toLong()), 4L)
            return IntSize(width, Math.toIntExact((bytes - output + width * 4L - 1L) / (width * 4L)))
        }

    /**
     * Proves identical ordered output from exact metadata and the same immutable source identities.
     * Placement is irrelevant only when every original-coordinate axis and coverage entry agrees.
     */
    @JvmSynthetic
    internal fun equivalent(other: FabricMinecraftCompositionMap): Boolean {
        if (this === other) return true
        if (physicalSize != other.physicalSize || sources.size != other.sources.size) return false
        if (sources.indices.any { sources[it] !== other.sources[it] }) return false
        return samePixels(indices, other.indices) && samePixels(factors, other.factors)
    }

    private fun samePixels(
        a: DrawImage,
        b: DrawImage,
    ): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (y in 0 until a.size.height) {
            for (x in 0 until a.size.width) if (a.argbAt(x, y) != b.argbAt(x, y)) return false
        }
        return true
    }

    /**
     * Constructs bounded descriptions without reading source pixels or translating Float coordinates.
     */
    internal companion object {
        /**
         * Keeps inexpensive repeated-source CPU spans out of production offscreen composition.
         * A sampled crop must cover at least 16,384 source texels before any tile metadata is allocated.
         * This performance admission reads only immutable extents; the exact composer supports smaller sources too.
         */
        @JvmSynthetic
        internal fun shouldCompose(commands: List<DrawCommand>): Boolean =
            commands.any { command ->
                if (command is DrawCommand.SampledImage) {
                    val imageSize = command.image.size
                    val width = ceil(command.source.width.toDouble()).coerceIn(0.0, imageSize.width.toDouble()).toLong()
                    val height = ceil(command.source.height.toDouble()).coerceIn(0.0, imageSize.height.toDouble()).toLong()
                    16384L <= width * height
                } else {
                    false
                }
            }

        /**
         * Returns null for a small, unsupported or exhausted whole tile, leaving its existing CPU path intact.
         */
        @JvmSynthetic
        internal fun create(
            commands: List<DrawCommand>,
            size: IntSize,
            scale: Int,
            origin: IntOffset,
            budget: FabricMinecraftSamplingBudget,
        ): FabricMinecraftCompositionMap? {
            require(0 < size.width && 0 < size.height && 0 < scale && 0 <= origin.x && 0 <= origin.y)
            val physical = IntSize(Math.multiplyExact(size.width, scale), Math.multiplyExact(size.height, scale))
            if (physical.width !in 1..4096 || physical.height !in 1..4096 || physical.width.toLong() * physical.height < 4096L) return null
            if (commands.none { it is DrawCommand.SampledImage }) return null
            return create(FabricMinecraftPortableImage(commands, size, scale, origin), budget, FabricMinecraftCompositionFactors())
        }

        /**
         * Uses one preparation-local factor workspace after reserving this tile's complete unchanged payload.
         * [axes] requires the caller's bounded exact original-axis proof; its immutable words are copied into a new owned array.
         * All current control rows and source references are rebuilt, without mutating or retaining the preceding map.
         * [axisEntriesWritten] records actual axis-row writes during construction; copied axes perform none.
         * No native storage or source pixels are read, and unsupported/exhausted tiles retain ordinary CPU fallback.
         */
        @JvmSynthetic
        internal fun create(
            input: FabricMinecraftPortableImage,
            budget: FabricMinecraftSamplingBudget,
            factorTables: FabricMinecraftCompositionFactors,
            axes: FabricMinecraftCompositionMap? = null,
        ): FabricMinecraftCompositionMap? {
            val commands = input.commands
            val size = input.size
            val scale = input.scale
            val origin = input.origin
            val physical = physicalSize(input) ?: return null
            if (commands.none { it is DrawCommand.SampledImage }) return null
            val geometry = Geometry(size, scale, origin)
            val plans = geometry.plans(commands) ?: return null
            if (plans.isEmpty()) return null
            val tints = plans.mapNotNull { (it.command as? DrawCommand.SampledImage)?.tint?.value }.distinct()
            val indexSize = IntSize(maxOf(4, physical.width, physical.height), plans.size * 3)
            val factorSize = IntSize(1536, maxOf(1, tints.size))
            val metadata = indexSize.width.toLong() * indexSize.height + factorSize.width.toLong() * factorSize.height
            if (budget.admitComposition(physical, metadata, plans.size).not()) return null
            val previous = axes?.takeIf { it.physicalSize == physical && it.indices.size == indexSize }
            val indices = previous?.indices?.copyArgb() ?: IntArray(Math.multiplyExact(indexSize.width, indexSize.height))
            var written = 0L
            plans.forEachIndexed { index, plan ->
                if (previous == null) {
                    geometry.writeAxes(indices, indexSize.width, index * 3, plan)
                    written += plan.coverage.width.toLong() + plan.coverage.height
                }
                writeControls(indices, (index * 3 + 2) * indexSize.width, plan.command, tints)
            }
            val factors = factorTables.get(tints, previous?.takeIf { it.orderedTints == tints }?.factors)
            return FabricMinecraftCompositionMap(physical, plans.map { source(it.command) }, createOwnedDrawImage(indexSize, indices), factors, tints, written)
        }

        private fun physicalSize(input: FabricMinecraftPortableImage): IntSize? {
            val size = input.size
            val scale = input.scale
            val origin = input.origin
            require(0 < size.width && 0 < size.height && 0 < scale && 0 <= origin.x && 0 <= origin.y)
            val physical = IntSize(Math.multiplyExact(size.width, scale), Math.multiplyExact(size.height, scale))
            if (physical.width !in 1..4096 || physical.height !in 1..4096 || physical.width.toLong() * physical.height < 4096L) return null
            return physical
        }

        private fun writeControls(
            indices: IntArray,
            offset: Int,
            command: DrawCommand,
            tints: List<Int>,
        ) {
            val kind =
                when (command) {
                    is DrawCommand.FillRectangle -> Kind.Fill
                    is DrawCommand.SampledImage -> Kind.Sampled
                    else -> Kind.IntegerImage
                }
            indices[offset] = encode(kind.ordinal)
            if (command is DrawCommand.FillRectangle) indices[offset + 1] = encode(command.color.value)
            if (command is DrawCommand.SampledImage) {
                indices[offset + 2] = encode(if (command.alphaCutoff == 0f) 0 else command.alphaCutoff.toRawBits())
                indices[offset + 3] = encode(tints.indexOf(command.tint.value))
            }
        }

        private fun source(command: DrawCommand): DrawImage? =
            when (command) {
                is DrawCommand.BlitImage -> command.image
                is DrawCommand.BlitImagePixels -> command.image
                is DrawCommand.SampledImage -> command.image
                else -> null
            }

        /**
         * Encodes a little-endian uint word as ordinary ARGB upload pixels for the nested geometry writer.
         */
        @JvmSynthetic
        internal fun encode(value: Int): Int = ((value and 255) shl 16) or ((value ushr 8 and 255) shl 8) or (value ushr 16 and 255) or (value ushr 24 shl 24)
    }

    /**
     * Wire values decoded only at the native shader boundary; the sealed DrawCommand visitor determines the domain kind.
     */
    private enum class Kind { Fill, IntegerImage, Sampled }

    private class Plan(
        val command: DrawCommand,
        val coverage: IntRect,
    )

    private class Geometry(
        size: IntSize,
        private val scale: Int,
        origin: IntOffset,
    ) {
        private val logical = IntRect(origin.x, origin.y, Math.addExact(origin.x, size.width), Math.addExact(origin.y, size.height))
        private val physical = IntRect(Math.multiplyExact(logical.left, scale), Math.multiplyExact(logical.top, scale), Math.multiplyExact(logical.right, scale), Math.multiplyExact(logical.bottom, scale))

        @Suppress("CyclomaticComplexMethod") // One ordered visitor preserves nested physical clipping and admits only complete portable commands.
        fun plans(commands: List<DrawCommand>): List<Plan>? {
            val result = ArrayList<Plan>()
            val clips = arrayListOf(physical)
            for (command in commands) {
                when (command) {
                    is DrawCommand.PushClip -> {
                        clips.add(intersect(clips.last(), integer(command.bounds)))
                    }

                    is DrawCommand.PushFractionalClip -> {
                        clips.add(intersect(clips.last(), fractional(command.bounds)))
                    }

                    DrawCommand.PopClip -> {
                        require(1 < clips.size) { "Clip pop has no matching push." }
                        clips.removeAt(clips.lastIndex)
                    }

                    is DrawCommand.Platform -> {
                        return null
                    }

                    else -> {
                        val bounds =
                            when (command) {
                                is DrawCommand.FillRectangle -> integer(command.bounds)
                                is DrawCommand.BlitImage -> integer(command.destination)
                                is DrawCommand.BlitImagePixels -> integer(command.destination)
                                is DrawCommand.SampledImage -> fractional(command.destination)
                            }
                        val coverage = intersect(clips.last(), bounds)
                        if (0 < coverage.width && 0 < coverage.height && command.isFabricMinecraftCompositionNoOp().not()) {
                            if (1024 <= result.size) return null
                            result.add(Plan(command, coverage))
                        }
                    }
                }
                if (1024 < clips.size) return null
            }
            require(clips.size == 1) { "Clip push has no matching pop." }
            return result
        }

        fun writeAxes(
            pixels: IntArray,
            width: Int,
            row: Int,
            plan: Plan,
        ) {
            for (x in plan.coverage.left until plan.coverage.right) pixels[row * width + x - physical.left] = encode(axis(x, true, plan.command) + 1)
            for (y in plan.coverage.top until plan.coverage.bottom) pixels[(row + 1) * width + y - physical.top] = encode(axis(y, false, plan.command) + 1)
        }

        @Suppress("CyclomaticComplexMethod") // Logical-center blits and physical-center sampling keep their distinct original arithmetic.
        private fun axis(position: Int, horizontal: Boolean, command: DrawCommand): Int =
            when (command) {
                is DrawCommand.FillRectangle -> 0
                is DrawCommand.BlitImage -> integerAxis(position / scale, if (horizontal) command.source.left else command.source.top, if (horizontal) command.source.width else command.source.height, if (horizontal) command.destination.left else command.destination.top, if (horizontal) command.destination.width else command.destination.height, 1)
                is DrawCommand.BlitImagePixels -> integerAxis(position, if (horizontal) command.source.left else command.source.top, if (horizontal) command.source.width else command.source.height, if (horizontal) command.destination.left else command.destination.top, if (horizontal) command.destination.width else command.destination.height, scale)
                is DrawCommand.SampledImage -> sampledAxis(position, horizontal, command)
                else -> error("Clip commands have no source axis.")
            }

        private fun integerAxis(
            position: Int,
            sourceStart: Int,
            sourceExtent: Int,
            destinationStart: Int,
            destinationExtent: Int,
            density: Int,
        ): Int {
            val center = (position.toLong() - destinationStart.toLong() * density) * 2L + 1L
            val denominator = destinationExtent.toLong() * density * 2L
            val extent = sourceExtent.toLong()
            val offset =
                if (center <= Long.MAX_VALUE / extent) {
                    center * extent / denominator
                } else {
                    BigInteger
                        .valueOf(center)
                        .multiply(BigInteger.valueOf(extent))
                        .divide(BigInteger.valueOf(denominator))
                        .longValueExact()
                }
            return Math.toIntExact(sourceStart.toLong() + offset)
        }

        private fun sampledAxis(
            position: Int,
            horizontal: Boolean,
            command: DrawCommand.SampledImage,
        ): Int {
            val source = command.source
            val destination = command.destination
            val reversed = if (horizontal) command.orientation.flipX else command.orientation.flipY
            val start = if (horizontal) source.left else source.top
            val end = if (horizontal) source.right else source.bottom
            val from = if (horizontal) destination.left else destination.top
            val to = if (horizontal) destination.right else destination.bottom
            val extent = if (horizontal) command.image.size.width else command.image.size.height
            val center = (position.toFloat() + 0.5f) / scale.toFloat()
            val relative = (center - from) / (to - from)
            val sample = (if (reversed) end else start) * (1f - relative) + (if (reversed) start else end) * relative
            return floor(sample).toInt().coerceIn(0, extent - 1)
        }

        private fun integer(bounds: IntRect): IntRect = IntRect(bounds.left.coerceIn(logical.left, logical.right) * scale, bounds.top.coerceIn(logical.top, logical.bottom) * scale, bounds.right.coerceIn(logical.left, logical.right) * scale, bounds.bottom.coerceIn(logical.top, logical.bottom) * scale)

        private fun fractional(bounds: FloatRect): IntRect = IntRect(edge(bounds.left, physical.left, physical.right), edge(bounds.top, physical.top, physical.bottom), edge(bounds.right, physical.left, physical.right), edge(bounds.bottom, physical.top, physical.bottom))

        private fun edge(
            value: Float,
            start: Int,
            end: Int,
        ): Int = ceil(value.toDouble() * scale - 0.5).coerceIn(start.toDouble(), end.toDouble()).toInt()

        private fun intersect(
            a: IntRect,
            b: IntRect,
        ): IntRect {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            return IntRect(left, top, maxOf(left, minOf(a.right, b.right)), maxOf(top, minOf(a.bottom, b.bottom)))
        }
    }
}
