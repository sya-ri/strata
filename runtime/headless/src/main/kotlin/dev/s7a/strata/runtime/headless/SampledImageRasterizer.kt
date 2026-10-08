package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Samples fractional image commands directly into caller-owned physical raster storage.
 *
 * The object retains no images or raster state and may be called concurrently with independent destination arrays.
 * The caller exclusively owns the mutable destination for the duration of [paint].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object SampledImageRasterizer {
    /**
     * Paints one validated command using final-density pixel centers and continuous tint multiplication.
     *
     * @param pixels the exclusively owned physical ARGB raster matching [physicalSize].
     * @param physicalSize the positive physical destination extent.
     * @param scale the positive integer logical-to-physical density.
     * @param command the immutable sampled-image command.
     * @param clip the absolute physical clip already intersected with the output region.
     * @param origin nonnegative absolute physical origin of [pixels]; source sampling remains absolute.
     * The caller guarantees that clip coordinates relative to [origin] fit in [physicalSize].
     * Clipping preserves the source mapping from the original destination and the method retains no arguments.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // Keep exact scalar and span reuse in one ordered traversal without per-texel dispatch.
    fun paint(
        pixels: IntArray,
        physicalSize: IntSize,
        scale: Int,
        command: DrawCommand.SampledImage,
        clip: IntRect,
        origin: IntOffset = IntOffset.Zero,
        scratch: HeadlessRasterScratch? = null,
    ) {
        val destination = command.destination
        val left = maxOf(firstPixel(destination.left, scale), clip.left)
        val top = maxOf(firstPixel(destination.top, scale), clip.top)
        val right = minOf(firstPixel(destination.right, scale), clip.right)
        val bottom = minOf(firstPixel(destination.bottom, scale), clip.bottom)
        if (right <= left || bottom <= top) return
        if (command.tint.value ushr 24 == 0) return

        val mapColumns = 4 <= bottom - top && 4096L <= (right - left).toLong() * (bottom - top)
        val constantImage = command.image.size.width == 1 && command.image.size.height == 1
        val tables = mapColumns && constantImage.not()
        val weights = if (tables) scratch?.weights(command.tint.value) else null
        val color = SampledColor(command.tint.value, command.alphaCutoff, tables, weights)
        if (constantImage) {
            color.paintConstant(pixels, physicalSize.width, left - origin.x, top - origin.y, right - origin.x, bottom - origin.y, command.image.argbAt(0, 0))
            return
        }
        val sourceXs =
            if (mapColumns) {
                IntArray(right - left) { offset ->
                    sampleX(left + offset, scale, command)
                }
            } else {
                null
            }
        var previousSource = 0
        var previousDestination = 0
        var previousResult = 0
        val rows =
            if (mapColumns && command.source.height * 2 <= bottom - top) RowReuse(physicalSize.width, left - origin.x, right - origin.x) else null
        val opaqueTint = command.tint.value ushr 24 == 255
        val magnified = sourceXs != null && command.source.width * 4 <= right - left
        // Transparent black over transparent black is already a valid zero result for any tint or cutoff.
        for (y in top until bottom) {
            val sourceY =
                sampleCoordinate(
                    y,
                    scale,
                    destination.top,
                    destination.bottom,
                    if (command.orientation.flipY) command.source.bottom else command.source.top,
                    if (command.orientation.flipY) command.source.top else command.source.bottom,
                    command.image.size.height,
                )
            if (rows?.prepare(pixels, y - origin.y, sourceY) == true) continue
            if (magnified) {
                val start = (y - origin.y) * physicalSize.width + left - origin.x
                val opaqueRow = paintMagnifiedRow(pixels, start, sourceXs, sourceY, command.image, color, opaqueTint)
                rows?.finish(opaqueRow)
            } else {
                var opaqueRow = opaqueTint
                var index = (y - origin.y) * physicalSize.width + left - origin.x
                for (x in left until right) {
                    val sourceX =
                        sourceXs?.get(x - left) ?: sampleX(x, scale, command)
                    val source = command.image.argbAt(sourceX, sourceY)
                    if (rows != null && source ushr 24 != 255) opaqueRow = false
                    if (source ushr 24 == 0) {
                        index += 1
                        continue
                    }
                    val destinationColor = pixels[index]
                    // Keep repeated texels in the traversal; table construction and Float composition stay off this path.
                    if (source != previousSource || destinationColor != previousDestination) {
                        previousSource = source
                        previousDestination = destinationColor
                        previousResult = color.blend(source, destinationColor)
                    }
                    pixels[index] = previousResult
                    index += 1
                }
                rows?.finish(opaqueRow)
            }
        }
    }

    // Consecutive output columns with one selected texel borrow one source value.
    // Opaque source-over is destination-independent; translucent spans still blend each actual destination.
    @Suppress("LongParameterList")
    private fun paintMagnifiedRow(
        pixels: IntArray,
        start: Int,
        columns: IntArray,
        sourceY: Int,
        image: DrawImage,
        color: SampledColor,
        opaqueTint: Boolean,
    ): Boolean {
        var offset = 0
        var opaqueRow = opaqueTint
        while (offset < columns.size) {
            val sourceX = columns[offset]
            var end = offset + 1
            while (end < columns.size && columns[end] == sourceX) end += 1
            val source = image.argbAt(sourceX, sourceY)
            if (opaqueTint && source ushr 24 == 255) {
                pixels.fill(color.blend(source, 0), start + offset, start + end)
            } else {
                opaqueRow = false
                if (source ushr 24 != 0) {
                    for (index in start + offset until start + end) pixels[index] = color.blend(source, pixels[index])
                }
            }
            offset = end
        }
        return opaqueRow
    }

    private fun sampleX(
        physical: Int,
        scale: Int,
        command: DrawCommand.SampledImage,
    ): Int =
        sampleCoordinate(
            physical,
            scale,
            command.destination.left,
            command.destination.right,
            if (command.orientation.flipX) command.source.right else command.source.left,
            if (command.orientation.flipX) command.source.left else command.source.right,
            command.image.size.width,
        )

    private fun firstPixel(
        edge: Float,
        scale: Int,
    ): Int = ceil(edge.toDouble() * scale.toDouble() - 0.5).toInt()

    private fun sampleCoordinate(
        physical: Int,
        scale: Int,
        destinationStart: Float,
        destinationEnd: Float,
        sourceStart: Float,
        sourceEnd: Float,
        sourceSize: Int,
    ): Int {
        val center = (physical.toFloat() + 0.5f) / scale.toFloat()
        val relative = (center - destinationStart) / (destinationEnd - destinationStart)
        val sample = sourceStart * (1f - relative) + sourceEnd * relative
        return floor(sample).toInt().coerceIn(0, sourceSize - 1)
    }

    /**
     * Borrows only the immediately preceding output row within one paint invocation.
     * Opaque rows are destination-independent; other rows require exact original-destination equality.
     * A lazily owned input span is bounded by the clipped width and becomes unreachable when paint returns.
     */
    private class RowReuse(
        private val width: Int,
        private val left: Int,
        private val right: Int,
    ) {
        private var sourceY = -1
        private var opaque = false
        private var input: IntArray? = null

        fun prepare(
            pixels: IntArray,
            y: Int,
            sourceY: Int,
        ): Boolean {
            val start = y * width + left
            val end = y * width + right
            if (this.sourceY == sourceY) {
                val previousInput = input
                if (opaque || (previousInput != null && Arrays.equals(previousInput, 0, previousInput.size, pixels, start, end))) {
                    pixels.copyInto(pixels, start, start - width, end - width)
                    return true
                }
                if (previousInput == null) input = IntArray(right - left)
            }
            input?.let { pixels.copyInto(it, 0, start, end) }
            this.sourceY = sourceY
            return false
        }

        fun finish(opaque: Boolean) {
            this.opaque = opaque
        }
    }

    private class SampledColor(
        private val tint: Int,
        private val cutoff: Float,
        private val useChannelTables: Boolean,
        private val weights: SampledSourceWeights?,
    ) {
        private val identityTint = tint == 0xFFFFFFFF.toInt()
        private val alpha = normalized(tint ushr 24)
        private val red = normalized(tint ushr 16)
        private val green = normalized(tint ushr 8)
        private val blue = normalized(tint)
        private var previousSource = 0
        private var previousDestination = 0
        private var previousResult = 0
        private var opaquePalette: IntArray? = null
        private var blendTablesAllowed = useChannelTables
        private var blendTables: Array<IntArray?>? = null
        private var blendRowCount = 0
        private var paletteDestination = 0
        private var paletteDestinationKnown = false
        private var remainingPaletteSamples = 32

        // A whole one-texel image always clamps to (0, 0), including fractional sources and flips.
        // Pass the clipped scalar span without allocating a rectangle for each command.
        @Suppress("LongParameterList")
        fun paintConstant(
            pixels: IntArray,
            width: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            source: Int,
        ) {
            val sourceAlpha = weights?.alpha(source ushr 24) ?: (normalized(source ushr 24) * alpha)
            if (sourceAlpha < cutoff || sourceAlpha == 0f) return
            if (sourceAlpha == 1f) {
                val result = blend(source, 0)
                for (y in top until bottom) pixels.fill(result, y * width + left, y * width + right)
                return
            }
            var previousDestination = pixels[top * width + left]
            var previousResult = blend(source, previousDestination)
            val rows =
                if (4 <= bottom - top && 4096L <= (right - left).toLong() * (bottom - top)) RowReuse(width, left, right) else null
            for (y in top until bottom) {
                if (rows?.prepare(pixels, y, 0) == true) continue
                for (index in y * width + left until y * width + right) {
                    val destination = pixels[index]
                    if (destination != previousDestination) {
                        previousDestination = destination
                        previousResult = blend(source, destination)
                    }
                    pixels[index] = previousResult
                }
                rows?.finish(false)
            }
        }

        fun blend(
            source: Int,
            destination: Int,
        ): Int {
            val sourceAlphaByte = source ushr 24
            if (sourceAlphaByte == 0 || alpha == 0f) return destination
            if (sourceAlphaByte == 255 && alpha == 1f) return opaque(source)
            if ((source and 0xFFFFFF) == 0xFFFFFF && destination == (tint or 0xFF000000.toInt())) return destination
            if (source == previousSource && destination == previousDestination) return previousResult
            return translucent(source, destination)
        }

        // Keep repeated magnified-texel dispatch separate from channel-table construction and full Float composition.
        private fun translucent(
            source: Int,
            destination: Int,
        ): Int {
            val sourceAlpha = weights?.alpha(source ushr 24) ?: (normalized(source ushr 24) * alpha)
            if (sourceAlpha < cutoff || sourceAlpha == 0f) return destination
            val inverse = weights?.inverse(source ushr 24) ?: (1f - sourceAlpha)
            val destinationWeight = normalized(destination ushr 24) * inverse
            val outputAlpha = sourceAlpha + destinationWeight
            val alphaByte = quantize(outputAlpha)
            if (alphaByte == 0) return remember(source, destination, 0)
            if (blendTablesAllowed) {
                if (paletteDestinationKnown && destination != paletteDestination) {
                    blendTablesAllowed = false
                    blendTables = null
                } else {
                    paletteDestination = destination
                    paletteDestinationKnown = true
                    if (0 < remainingPaletteSamples) remainingPaletteSamples -= 1
                }
            }
            val sourceRow = weights?.row(source ushr 24)
            val outputRed = channel(source, destination, 16, sourceAlpha, destinationWeight, outputAlpha, sourceRow)
            val outputGreen = channel(source, destination, 8, sourceAlpha, destinationWeight, outputAlpha, sourceRow)
            val outputBlue = channel(source, destination, 0, sourceAlpha, destinationWeight, outputAlpha, sourceRow)
            return remember(source, destination, (alphaByte shl 24) or (outputRed shl 16) or (outputGreen shl 8) or outputBlue)
        }

        private fun opaque(source: Int): Int {
            if (identityTint) return source
            if (source == 0xFFFFFFFF.toInt()) return tint
            if (source == previousSource) return previousResult
            return remember(source, 0, tintedOpaque(source))
        }

        private fun tintedOpaque(source: Int): Int {
            val palette =
                if (useChannelTables) {
                    opaquePalette ?: IntArray(768) { index ->
                        val channelTint =
                            when (index / 256) {
                                0 -> red
                                1 -> green
                                else -> blue
                            }
                        quantize(normalized(index) * channelTint)
                    }.also { opaquePalette = it }
                } else {
                    null
                }
            val outputRed = palette?.get(source ushr 16 and 255) ?: quantize(normalized(source ushr 16) * red)
            val outputGreen = palette?.get(256 + (source ushr 8 and 255)) ?: quantize(normalized(source ushr 8) * green)
            val outputBlue = palette?.get(512 + (source and 255)) ?: quantize(normalized(source) * blue)
            return 0xFF000000.toInt() or (outputRed shl 16) or (outputGreen shl 8) or outputBlue
        }

        // Tint and cutoff are fixed for this command. Opaque results depend only on source;
        // translucent results additionally compare the complete destination, including transparent RGB.
        private fun remember(
            source: Int,
            destination: Int,
            result: Int,
        ): Int {
            previousSource = source
            previousDestination = destination
            previousResult = result
            return result
        }

        @Suppress("LongParameterList") // The borrowed source row is selected once for all three ordered channel calculations.
        private fun channel(
            source: Int,
            destination: Int,
            shift: Int,
            sourceAlpha: Float,
            destinationWeight: Float,
            outputAlpha: Float,
            sourceRow: FloatArray?,
        ): Int {
            // A table belongs to this command and one complete destination ARGB. Any unequal destination
            // permanently drops these rows. Observe repeated uniform blends before allocating, so early changes
            // retain only scalar admission state and never allocate tables that cannot be reused.
            val rows =
                if (blendTablesAllowed && remainingPaletteSamples == 0) {
                    blendTables ?: arrayOfNulls<IntArray>(256).also { blendTables = it }
                } else {
                    null
                }
            val sourceAlphaByte = source ushr 24
            val row =
                rows?.let {
                    it[sourceAlphaByte] ?: if (blendRowCount < 16) {
                        IntArray(768) { -1 }.also { table ->
                            it[sourceAlphaByte] = table
                            blendRowCount += 1
                        }
                    } else {
                        null
                    }
                }
            val index = (2 - shift / 8) * 256 + (source ushr shift and 255)
            val cached = row?.get(index)
            if (cached != null && 0 <= cached) return cached
            val contribution = contribution(source, shift, sourceAlpha, sourceRow)
            val result = quantize((contribution + normalized(destination ushr shift) * destinationWeight) / outputAlpha)
            if (row != null) row[index] = result
            return result
        }

        private fun contribution(
            source: Int,
            shift: Int,
            sourceAlpha: Float,
            sourceRow: FloatArray?,
        ): Float {
            sourceRow?.let { return it[(2 - shift / 8) * 256 + (source ushr shift and 255)] }
            val channelTint =
                when (shift) {
                    16 -> red
                    8 -> green
                    else -> blue
                }
            return normalized(source ushr shift) * channelTint * sourceAlpha
        }

        private fun normalized(channel: Int): Float = (channel and 0xFF).toFloat() / 255f

        private fun quantize(channel: Float): Int = (channel * 255f).roundToInt().coerceIn(0, 255)
    }
}
