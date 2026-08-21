@file:JvmName("HeadlessRendering")

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Rasterizes portable draw commands into a deterministic physical image.
 *
 * Commands are snapshotted in their supplied order before pixel allocation or painting.
 * The caller must not mutate the supplied list or command graph concurrently with this call.
 * Each call owns its raster storage and shares no mutable state with another call.
 * Rectangles use the logical origin at the top-left, x increasing rightward, y increasing downward, and half-open edges; the positive [viewport] and every active nested child clip are intersected before each logical pixel is replicated by [scale].
 * BlitImage uses nearest pixel-center sampling: for destination-relative coordinate `d`, source extent `S`, and destination extent `D`, the sampled source offset is `floor(((2 * d + 1) * S) / (2 * D))`; viewport clipping preserves this mapping against the original unclipped destination rectangle.
 * Painting starts with transparent black and uses straight ARGB source-over with Long intermediates.
 * For source alpha `sa`, destination alpha `da`, and channel values `sc` and `dc`, `alphaN = sa * 255 + da * (255 - sa)`, `oa = floor((alphaN + 127) / 255)`, and when `alphaN != 0` each channel is `floor((sc * sa * 255 + dc * da * (255 - sa) + floor(alphaN / 2)) / alphaN)`.
 * When `alphaN == 0`, the result is exactly `0x00000000`.
 * Each channel and alpha is rounded half-up per command, with canonical zero for transparent output.
 * Transparent sources are no-ops, opaque sources replace, and no gamma conversion, interpolation, or saturation is applied.
 *
 * @param commands the core-emitted draw commands in execution order; opaque platform commands are unsupported.
 * @param viewport the positive logical viewport.
 * @param scale the positive integer logical-to-physical scale.
 * @return an immutable physical ARGB image with transparent-black initial pixels.
 * The physical size is the checked viewport width and height multiplied by [scale].
 * @throws IllegalArgumentException when the viewport or scale is invalid, a command is unsupported or null from Java, or the clip stack is unbalanced.
 * @throws ArithmeticException when checked physical dimensions, pixel area, or derived raster storage exceeds Int.MAX_VALUE.
 */
@JvmOverloads
public fun rasterizeHeadless(
    commands: List<DrawCommand>,
    viewport: IntSize,
    scale: Int = 1,
): HeadlessImage = HeadlessImplementation.rasterize(commands, viewport, scale)

/**
 * Synchronously renders an element description through the retained core and rasterizes its paint output.
 *
 * Viewport and physical-size validation occurs before the description is validated or any node lifecycle hook runs.
 * Render callbacks and temporary-tree cleanup run on the calling thread, and the temporary tree is always closed after creation.
 * Semantics remain logical, unscaled, unclipped, unmodified, and in core emission order.
 *
 * @param description the caller-owned immutable root description.
 * @param viewport the positive logical fixed viewport.
 * @param scale the positive integer logical-to-physical scale.
 * @return an immutable frame containing the physical image and logical semantics.
 * @throws IllegalArgumentException when the viewport or scale is invalid, or a command is unsupported or null from Java.
 * @throws ArithmeticException when checked physical dimensions, pixel area, or derived raster storage exceeds Int.MAX_VALUE.
 * @throws Throwable when core work or cleanup fails; the exact work failure remains primary and distinct cleanup failures are suppressed once.
 */
@JvmOverloads
public fun renderHeadless(
    description: Element,
    viewport: IntSize,
    scale: Int = 1,
): HeadlessFrame = HeadlessImplementation.render(description, viewport, scale)

/**
 * Owns the private implementation of the public headless facade.
 */
@OptIn(InternalStrataRuntimeApi::class)
private object HeadlessImplementation {
    fun rasterize(
        commands: List<DrawCommand>,
        viewport: IntSize,
        scale: Int,
    ): HeadlessImage {
        val dimensions = checkedDimensions(viewport, scale)
        val snapshot = snapshotCommands(commands)
        val pixels = IntArray(dimensions.area)
        paintSnapshot(pixels, dimensions, snapshot)
        return ImageImpl.create(dimensions.physicalSize, pixels)
    }

    fun render(
        description: Element,
        viewport: IntSize,
        scale: Int,
    ): HeadlessFrame {
        val dimensions = checkedDimensions(viewport, scale)
        val tree = UiTree()
        return completeWithClose(
            work = {
                tree.update(description)
                val measured = tree.measure(Constraints.fixed(viewport.width, viewport.height))
                check(measured == viewport) {
                    "The retained root did not report the fixed headless viewport."
                }
                tree.layout()
                val commands = tree.paint()
                val semantics = tree.semantics()
                val image = rasterizeSnapshot(commands, dimensions)
                FrameImpl.create(viewport, scale, image, semantics)
            },
            close = tree::close,
        )
    }

    private fun rasterizeSnapshot(
        commands: List<DrawCommand>,
        dimensions: PhysicalDimensions,
    ): HeadlessImage {
        val snapshot = snapshotCommands(commands)
        val pixels = IntArray(dimensions.area)
        paintSnapshot(pixels, dimensions, snapshot)
        return ImageImpl.create(dimensions.physicalSize, pixels)
    }

    private fun snapshotCommands(commands: List<DrawCommand>): List<DrawCommand> {
        var clipDepth = 0
        val snapshot = ArrayList<DrawCommand>(commands.size)
        commands.forEach { command ->
            val checkedCommand = requireNotNull(command) { "Unsupported or null draw command." }
            when (checkedCommand) {
                is DrawCommand.FillRectangle -> {
                    snapshot.add(checkedCommand)
                }

                is DrawCommand.BlitImage -> {
                    snapshot.add(checkedCommand)
                }

                is DrawCommand.Platform -> {
                    throw IllegalArgumentException("Headless rendering does not support platform draw commands.")
                }

                is DrawCommand.PushClip -> {
                    clipDepth = Math.incrementExact(clipDepth)
                    snapshot.add(checkedCommand)
                }

                DrawCommand.PopClip -> {
                    require(0 < clipDepth) { "Clip pop has no matching push." }
                    clipDepth -= 1
                    snapshot.add(checkedCommand)
                }
            }
        }
        require(clipDepth == 0) { "Clip push has no matching pop." }
        return snapshot
    }

    private fun paintSnapshot(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        commands: List<DrawCommand>,
    ) {
        val clips = ArrayList<IntRect>()
        commands.forEach { command ->
            when (command) {
                is DrawCommand.FillRectangle -> {
                    paintFill(pixels, dimensions, command, clips.lastOrNull())
                }

                is DrawCommand.BlitImage -> {
                    paintBlit(pixels, dimensions, command, clips.lastOrNull())
                }

                is DrawCommand.Platform -> {
                    error("Platform draw commands are rejected during snapshot preflight.")
                }

                is DrawCommand.PushClip -> {
                    val outer = clips.lastOrNull() ?: IntRect(0, 0, dimensions.viewport.width, dimensions.viewport.height)
                    clips.add(RasterMath.intersection(outer, command.bounds))
                }

                DrawCommand.PopClip -> {
                    clips.removeAt(clips.lastIndex)
                }
            }
        }
    }

    private fun paintFill(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        command: DrawCommand.FillRectangle,
        clip: IntRect?,
    ) {
        val bounds = command.bounds
        val viewport = IntRect(0, 0, dimensions.viewport.width, dimensions.viewport.height)
        val visible = RasterMath.intersection(viewport, clip ?: bounds, bounds)
        val left = visible.left
        val top = visible.top
        val right = visible.right
        val bottom = visible.bottom
        if (right <= left || bottom <= top) {
            return
        }
        val source = command.color.value
        for (logicalY in top until bottom) {
            for (logicalX in left until right) {
                paintLogicalPixel(pixels, dimensions, logicalX, logicalY, source)
            }
        }
    }

    private fun paintBlit(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        command: DrawCommand.BlitImage,
        clip: IntRect?,
    ) {
        val bounds = command.destination
        val viewport = IntRect(0, 0, dimensions.viewport.width, dimensions.viewport.height)
        val visible = RasterMath.intersection(viewport, clip ?: bounds, bounds)
        val left = visible.left
        val top = visible.top
        val right = visible.right
        val bottom = visible.bottom
        if (right <= left || bottom <= top) {
            return
        }
        val sourceWidth = command.source.width.toLong()
        val sourceHeight = command.source.height.toLong()
        val destinationWidth = bounds.width.toLong()
        val destinationHeight = bounds.height.toLong()
        for (logicalY in top until bottom) {
            val destinationY = Math.subtractExact(logicalY, bounds.top)
            val sourceY = RasterMath.sampleSourceCoordinate(destinationY, command.source.top, sourceHeight, destinationHeight)
            for (logicalX in left until right) {
                val destinationX = Math.subtractExact(logicalX, bounds.left)
                val sourceX = RasterMath.sampleSourceCoordinate(destinationX, command.source.left, sourceWidth, destinationWidth)
                val sourceColor = command.image.argbAt(sourceX, sourceY)
                paintLogicalPixel(pixels, dimensions, logicalX, logicalY, sourceColor)
            }
        }
    }

    private fun paintLogicalPixel(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        logicalX: Int,
        logicalY: Int,
        source: Int,
    ) {
        val physicalX = Math.multiplyExact(logicalX, dimensions.scale)
        val physicalY = Math.multiplyExact(logicalY, dimensions.scale)
        val color = RasterMath.blend(source, pixels[index(dimensions, physicalX, physicalY)])
        for (dy in 0 until dimensions.scale) {
            val row = Math.multiplyExact(Math.addExact(physicalY, dy), dimensions.physicalSize.width)
            val first = Math.addExact(row, physicalX)
            for (dx in 0 until dimensions.scale) {
                pixels[Math.addExact(first, dx)] = color
            }
        }
    }

    private fun index(
        dimensions: PhysicalDimensions,
        x: Int,
        y: Int,
    ): Int = Math.addExact(Math.multiplyExact(y, dimensions.physicalSize.width), x)

    private fun checkedDimensions(
        viewport: IntSize,
        scale: Int,
    ): PhysicalDimensions {
        require(0 < viewport.width) { "Viewport width must be positive." }
        require(0 < viewport.height) { "Viewport height must be positive." }
        require(0 < scale) { "Pixel scale must be positive." }
        val physicalWidth = checkedMultiply(viewport.width, scale, "Physical width")
        val physicalHeight = checkedMultiply(viewport.height, scale, "Physical height")
        val area = checkedMultiply(physicalWidth, physicalHeight, "Physical pixel area")
        return PhysicalDimensions(viewport, IntSize(physicalWidth, physicalHeight), scale, area)
    }

    private fun checkedMultiply(
        first: Int,
        second: Int,
        label: String,
    ): Int =
        try {
            Math.multiplyExact(first, second)
        } catch (_: ArithmeticException) {
            throw ArithmeticException("$label exceeds Int.MAX_VALUE.")
        }

    private object RasterMath {
        fun intersection(
            first: IntRect,
            second: IntRect,
            third: IntRect = second,
        ): IntRect {
            val left = maxOf(first.left, second.left, third.left)
            val top = maxOf(first.top, second.top, third.top)
            val right = maxOf(left, minOf(first.right, second.right, third.right))
            val bottom = maxOf(top, minOf(first.bottom, second.bottom, third.bottom))
            return IntRect(left, top, right, bottom)
        }

        /**
         * Applies nearest pixel-center mapping with checked Long intermediates.
         *
         * Positive Int extents bound `(2 * d + 1) * S` below Long.MAX_VALUE, so every legal command is represented exactly.
         */
        fun sampleSourceCoordinate(
            destinationRelative: Int,
            sourceStart: Int,
            sourceExtent: Long,
            destinationExtent: Long,
        ): Int {
            val centerNumerator = Math.addExact(Math.multiplyExact(destinationRelative.toLong(), 2L), 1L)
            val numerator = Math.multiplyExact(centerNumerator, sourceExtent)
            val denominator = Math.multiplyExact(destinationExtent, 2L)
            val sourceOffset = numerator / denominator
            return Math.toIntExact(Math.addExact(sourceStart.toLong(), sourceOffset))
        }

        fun blend(
            source: Int,
            destination: Int,
        ): Int {
            val sourceAlpha = source ushr 24 and 0xFF
            val destinationAlpha = destination ushr 24 and 0xFF
            val alphaNumerator =
                sourceAlpha.toLong() * 255L + destinationAlpha.toLong() * (255 - sourceAlpha).toLong()
            if (alphaNumerator == 0L) {
                return 0
            }
            val outputAlpha = (alphaNumerator + 127L) / 255L
            val red =
                channel(
                    source ushr 16 and 0xFF,
                    destination ushr 16 and 0xFF,
                    sourceAlpha,
                    destinationAlpha,
                    alphaNumerator,
                )
            val green =
                channel(
                    source ushr 8 and 0xFF,
                    destination ushr 8 and 0xFF,
                    sourceAlpha,
                    destinationAlpha,
                    alphaNumerator,
                )
            val blue =
                channel(
                    source and 0xFF,
                    destination and 0xFF,
                    sourceAlpha,
                    destinationAlpha,
                    alphaNumerator,
                )
            return (outputAlpha.toInt() shl 24) or (red shl 16) or (green shl 8) or blue
        }

        private fun channel(
            source: Int,
            destination: Int,
            sourceAlpha: Int,
            destinationAlpha: Int,
            alphaNumerator: Long,
        ): Int {
            val numerator =
                source.toLong() * sourceAlpha.toLong() * 255L +
                    destination.toLong() * destinationAlpha.toLong() * (255 - sourceAlpha).toLong()
            return ((numerator + alphaNumerator / 2L) / alphaNumerator).toInt()
        }
    }

    private class ImageImpl private constructor(
        override val size: IntSize,
        private val pixels: IntArray,
    ) : HeadlessImage {
        override fun argbAt(
            x: Int,
            y: Int,
        ): Int {
            require(0 <= x && x < size.width) { "X coordinate must be inside the image." }
            require(0 <= y && y < size.height) { "Y coordinate must be inside the image." }
            val rowOffset = Math.multiplyExact(y, size.width)
            val index = Math.addExact(rowOffset, x)
            return pixels[index]
        }

        override fun copyArgb(): IntArray = pixels.copyOf()

        override fun encodePng(): ByteArray = PngEncoder.encode(size, pixels)

        companion object {
            @JvmSynthetic
            internal fun create(
                size: IntSize,
                pixels: IntArray,
            ): ImageImpl = ImageImpl(size, pixels)
        }
    }

    private class FrameImpl private constructor(
        override val viewport: IntSize,
        override val pixelScale: Int,
        override val image: HeadlessImage,
        semantics: List<SemanticsEntry>,
    ) : HeadlessFrame {
        override val semantics: List<SemanticsEntry> = immutableSnapshot(semantics)

        companion object {
            @JvmSynthetic
            internal fun create(
                viewport: IntSize,
                pixelScale: Int,
                image: HeadlessImage,
                semantics: List<SemanticsEntry>,
            ): FrameImpl = FrameImpl(viewport, pixelScale, image, semantics)
        }
    }

    private object PngEncoder {
        private val signature =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            )
        private const val MAX_STORED_BLOCK_LENGTH: Int = 65535
        private const val ADLER_MODULUS: Long = 65521L

        fun encode(
            size: IntSize,
            pixels: IntArray,
        ): ByteArray {
            val scanlines = scanlines(size, pixels)
            val compressed = zlib(scanlines)
            val ihdr = ByteArray(13)
            writeInt(ihdr, 0, size.width)
            writeInt(ihdr, 4, size.height)
            ihdr[8] = 8
            ihdr[9] = 6
            val output = ByteArrayOutput(sizeBytes(ihdr, compressed))
            output.write(signature)
            output.writeChunk("IHDR", ihdr)
            output.writeChunk("IDAT", compressed)
            output.writeChunk("IEND", ByteArray(0))
            return output.toByteArray()
        }

        private fun scanlines(
            size: IntSize,
            pixels: IntArray,
        ): ByteArray {
            val rowBytes = checkedAdd(checkedMultiply(size.width, 4, "PNG row width"), 1, "PNG row width")
            val totalBytes = checkedMultiply(rowBytes, size.height, "PNG scanline data")
            val scanlines = ByteArray(totalBytes)
            var target = 0
            var source = 0
            repeat(size.height) {
                scanlines[target] = 0
                target += 1
                repeat(size.width) {
                    val argb = pixels[source]
                    source += 1
                    scanlines[target] = (argb ushr 16).toByte()
                    scanlines[target + 1] = (argb ushr 8).toByte()
                    scanlines[target + 2] = argb.toByte()
                    scanlines[target + 3] = (argb ushr 24).toByte()
                    target += 4
                }
            }
            return scanlines
        }

        private fun zlib(data: ByteArray): ByteArray {
            val blockCount = data.size / MAX_STORED_BLOCK_LENGTH + if (data.size % MAX_STORED_BLOCK_LENGTH == 0) 0 else 1
            val deflateBytes = checkedAdd(data.size, checkedMultiply(blockCount, 5, "PNG stored-block headers"), "PNG deflate stream")
            val output = ByteArray(checkedAdd(deflateBytes, 6, "PNG zlib stream"))
            var target = 0
            output[target] = 0x78
            target += 1
            output[target] = 0x01
            target += 1
            var source = 0
            repeat(blockCount) { blockIndex ->
                val remaining = data.size - source
                val blockLength = minOf(remaining, MAX_STORED_BLOCK_LENGTH)
                val finalBlock = blockIndex == blockCount - 1
                output[target] = if (finalBlock) 0x01 else 0x00
                target += 1
                output[target] = blockLength.toByte()
                output[target + 1] = (blockLength ushr 8).toByte()
                val complement = blockLength.inv()
                output[target + 2] = complement.toByte()
                output[target + 3] = (complement ushr 8).toByte()
                target += 4
                data.copyInto(output, target, source, source + blockLength)
                target += blockLength
                source += blockLength
            }
            val adler = adler32(data)
            output[target] = (adler ushr 24).toByte()
            output[target + 1] = (adler ushr 16).toByte()
            output[target + 2] = (adler ushr 8).toByte()
            output[target + 3] = adler.toByte()
            return output
        }

        private fun adler32(data: ByteArray): Long {
            var first = 1L
            var second = 0L
            data.forEach { value ->
                first = (first + (value.toInt() and 0xFF)) % ADLER_MODULUS
                second = (second + first) % ADLER_MODULUS
            }
            return (second shl 16) or first
        }

        private fun sizeBytes(
            ihdr: ByteArray,
            idat: ByteArray,
        ): Int {
            var size = signature.size
            size = checkedAdd(size, chunkSize(ihdr.size), "PNG output")
            size = checkedAdd(size, chunkSize(idat.size), "PNG output")
            return checkedAdd(size, chunkSize(0), "PNG output")
        }

        private fun chunkSize(payloadSize: Int): Int = checkedAdd(payloadSize, 12, "PNG chunk")

        private fun checkedMultiply(
            first: Int,
            second: Int,
            label: String,
        ): Int =
            try {
                Math.multiplyExact(first, second)
            } catch (_: ArithmeticException) {
                throw ArithmeticException("$label exceeds Int.MAX_VALUE.")
            }

        private fun checkedAdd(
            first: Int,
            second: Int,
            label: String,
        ): Int =
            try {
                Math.addExact(first, second)
            } catch (_: ArithmeticException) {
                throw ArithmeticException("$label exceeds Int.MAX_VALUE.")
            }

        private fun writeInt(
            target: ByteArray,
            offset: Int,
            value: Int,
        ) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        private class ByteArrayOutput(
            initialCapacity: Int,
        ) {
            private val bytes = ByteArray(initialCapacity)
            private var position = 0

            fun write(source: ByteArray) {
                source.copyInto(bytes, position)
                position += source.size
            }

            fun writeChunk(
                type: String,
                payload: ByteArray,
            ) {
                writeInt(bytes, position, payload.size)
                position += 4
                val typeBytes = type.encodeToByteArray()
                typeBytes.copyInto(bytes, position)
                position += typeBytes.size
                payload.copyInto(bytes, position)
                position += payload.size
                val crc = crc32(typeBytes, payload)
                writeInt(bytes, position, crc)
                position += 4
            }

            fun toByteArray(): ByteArray = bytes.copyOf()

            private fun crc32(
                type: ByteArray,
                payload: ByteArray,
            ): Int {
                var crc = -1
                type.forEach { value -> crc = updateCrc(crc, value) }
                payload.forEach { value -> crc = updateCrc(crc, value) }
                return crc.inv()
            }

            private fun updateCrc(
                initial: Int,
                value: Byte,
            ): Int {
                var crc = initial xor (value.toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 1 == 1) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
                }
                return crc
            }
        }
    }

    private data class PhysicalDimensions(
        val viewport: IntSize,
        val physicalSize: IntSize,
        val scale: Int,
        val area: Int,
    )
}
