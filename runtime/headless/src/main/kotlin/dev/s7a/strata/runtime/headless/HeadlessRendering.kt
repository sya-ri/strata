@file:JvmName("HeadlessRendering")

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32
import java.util.zip.CRC32

/**
 * Rasterizes ordered portable commands into an immutable physical ARGB image.
 *
 * Commands are snapshotted before allocation; callers must not mutate the input graph concurrently.
 * Coordinates are top-left, x-right, y-down, with half-open edges clipped to the viewport and nested child clips.
 * Integer BlitImage samples logical pixel centers and replicates at [scale]; BlitImagePixels and SampledImage sample final physical pixel centers.
 * Clipping preserves the original source mapping. SampledImage applies normalized tint and alpha cutoff before blending.
 * Output starts transparent black and uses straight-ARGB source-over with half-up rounding per command, without gamma conversion.
 * Exact arithmetic is defined in the [rendering contract](https://github.com/sya-ri/strata/blob/master/docs/development/rendering.md#headless-rasterization).
 *
 * @param commands draw commands in execution order; opaque platform commands are unsupported.
 * @param viewport positive logical extent.
 * @param scale positive integer logical-to-physical scale.
 * @return a new image whose dimensions are the checked viewport dimensions multiplied by [scale].
 * @throws IllegalArgumentException for invalid dimensions/scale, unsupported commands, Java null commands, or unbalanced clips.
 * @throws ArithmeticException when physical dimensions, pixel area, or raster storage exceed Int.MAX_VALUE.
 */
@JvmOverloads
public fun rasterizeHeadless(
    commands: List<DrawCommand>,
    viewport: IntSize,
    scale: Int = 1,
): HeadlessImage = HeadlessImplementation.rasterize(commands, viewport, scale)

/**
 * Rasterizes a nonnegative logical region for runtime presentation without translating source-sampling coordinates.
 * Output storage contains only the checked physical extent of [bounds]; ordered commands retain their original coordinates.
 * Validation, clip balance, exact Float sampling and pixel composition follow [rasterizeHeadless].
 * No commands, pixels or region history are retained after the returned immutable image is released.
 */
@InternalStrataRuntimeApi
@JvmSynthetic
public fun rasterizeHeadlessRegion(
    commands: List<DrawCommand>,
    bounds: IntRect,
    scale: Int,
): HeadlessImage {
    require(0 <= bounds.left && 0 <= bounds.top) { "Raster region origin must be nonnegative." }
    return HeadlessImplementation.rasterize(commands, bounds.size, scale, IntOffset(bounds.left, bounds.top))
}

/**
 * Rasterizes an original-coordinate region into borrowed caller-owned ARGB storage for synchronous native upload.
 * Materializes the transparent background lazily and writes only the checked physical region prefix; excess capacity remains untouched.
 * Every output pixel is initialized before use, including empty commands and clipped primitives.
 * The caller exclusively owns [pixels] throughout this call, and no image, command, array or callback is retained.
 * Preflight rejects invalid input before any storage changes. Pixel arithmetic follows [rasterizeHeadlessRegion].
 */
@InternalStrataRuntimeApi
@JvmSynthetic
public fun rasterizeHeadlessInto(
    commands: List<DrawCommand>,
    bounds: IntRect,
    scale: Int,
    pixels: IntArray,
) {
    require(0 <= bounds.left && 0 <= bounds.top) { "Raster region origin must be nonnegative." }
    HeadlessImplementation.rasterizeInto(commands, bounds.size, scale, IntOffset(bounds.left, bounds.top), pixels)
}

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
@Suppress("TooManyFunctions") // Keeps primitive validation and ordered rasterization under the same private owner.
private object HeadlessImplementation {
    fun rasterizeInto(
        commands: List<DrawCommand>,
        viewport: IntSize,
        scale: Int,
        origin: IntOffset,
        pixels: IntArray,
    ) {
        val dimensions = checkedDimensions(viewport, scale, origin)
        require(dimensions.area <= pixels.size) { "Borrowed raster storage must cover the physical region." }
        val snapshot = snapshotCommands(commands)
        paintSnapshot(dimensions, snapshot, pixels)
    }

    fun rasterize(
        commands: List<DrawCommand>,
        viewport: IntSize,
        scale: Int,
        origin: IntOffset = IntOffset.Zero,
    ): HeadlessImage {
        val dimensions = checkedDimensions(viewport, scale, origin)
        return rasterizeSnapshot(commands, dimensions)
    }

    fun render(
        description: Element,
        viewport: IntSize,
        scale: Int,
    ): HeadlessFrame {
        val dimensions = checkedDimensions(viewport, scale)
        val session = createRuntimeUiSession { description }
        return completeWithClose(
            work = {
                session.attach()
                val frame = session.frame(Constraints.fixed(viewport.width, viewport.height))
                check(frame.size == viewport) {
                    "The retained root did not report the fixed headless viewport."
                }
                val image = rasterizeSnapshot(frame.drawCommands, dimensions)
                FrameImpl(viewport, scale, image, frame.semantics)
            },
            close = session::close,
        )
    }

    private fun rasterizeSnapshot(
        commands: List<DrawCommand>,
        dimensions: PhysicalDimensions,
    ): HeadlessImage {
        val snapshot = snapshotCommands(commands)
        return ImageImpl(dimensions.physicalSize, paintSnapshot(dimensions, snapshot))
    }

    private fun snapshotCommands(commands: List<DrawCommand>): List<DrawCommand> {
        var clipDepth = 0
        val snapshot =
            commands.map { command ->
                val checkedCommand = requireNotNull(command) { "Unsupported or null draw command." }
                when (checkedCommand) {
                    is DrawCommand.Platform -> {
                        throw IllegalArgumentException("Headless rendering does not support platform draw commands.")
                    }

                    is DrawCommand.PushClip, is DrawCommand.PushFractionalClip -> {
                        checkedCommand.also {
                            clipDepth = Math.incrementExact(clipDepth)
                        }
                    }

                    DrawCommand.PopClip -> {
                        checkedCommand.also {
                            require(0 < clipDepth) { "Clip pop has no matching push." }
                            clipDepth -= 1
                        }
                    }

                    is DrawCommand.FillRectangle, is DrawCommand.BlitImage, is DrawCommand.SampledImage, is DrawCommand.BlitImagePixels -> {
                        checkedCommand
                    }
                }
            }
        require(clipDepth == 0) { "Clip push has no matching pop." }
        return snapshot
    }

    @Suppress("CyclomaticComplexMethod") // Explicit command ordering includes materialization before each non-uniform primitive.
    private fun paintSnapshot(
        dimensions: PhysicalDimensions,
        commands: List<DrawCommand>,
        borrowed: IntArray? = null,
    ): IntArray {
        val initialPixels = if (borrowed == null) initialImagePixels(commands.firstOrNull(), dimensions) else null
        val pixels = borrowed ?: initialPixels ?: IntArray(dimensions.area)
        var uniform = initialPixels == null
        var uniformColor = 0
        val clips = ArrayList<IntRect>()
        val physicalViewport = dimensions.physicalBounds
        var index = if (initialPixels == null) 0 else 1
        while (index < commands.size) {
            if (uniform.not()) {
                val end = composeFullFillRun(pixels, dimensions, commands, index, clips.lastOrNull())
                if (index < end) {
                    index = end
                    continue
                }
            }
            when (val command = commands[index++]) {
                is DrawCommand.FillRectangle -> {
                    if ((uniform || command.color.value ushr 24 == 0xFF) && coversViewport(command.bounds, dimensions, clips.lastOrNull())) {
                        uniformColor = if (uniform) RasterMath.blend(command.color.value, uniformColor) else command.color.value
                        uniform = true
                    } else {
                        if (uniform) pixels.fill(uniformColor, 0, dimensions.area)
                        uniform = paintFill(pixels, dimensions, command, clips.lastOrNull())
                        if (uniform) uniformColor = pixels[0]
                    }
                }

                is DrawCommand.BlitImage -> {
                    if (uniform) pixels.fill(uniformColor, 0, dimensions.area)
                    uniform = false
                    paintBlit(pixels, dimensions, command, clips.lastOrNull())
                }

                is DrawCommand.SampledImage -> {
                    if (uniform) pixels.fill(uniformColor, 0, dimensions.area)
                    uniform = false
                    paintSampled(pixels, dimensions, command, clips.lastOrNull() ?: physicalViewport)
                }

                is DrawCommand.BlitImagePixels -> {
                    if (uniform) pixels.fill(uniformColor, 0, dimensions.area)
                    uniform = false
                    paintBlitPixels(pixels, dimensions, command, clips.lastOrNull())
                }

                is DrawCommand.Platform -> {
                    error("Platform draw commands are rejected during snapshot preflight.")
                }

                is DrawCommand.PushClip -> {
                    pushIntegerClip(clips, dimensions, command.bounds)
                }

                is DrawCommand.PushFractionalClip -> {
                    clips.add(RasterMath.intersection(clips.lastOrNull() ?: physicalViewport, RasterClips.physical(command.bounds, physicalViewport, dimensions.scale)))
                }

                DrawCommand.PopClip -> {
                    clips.removeAt(clips.lastIndex)
                }
            }
        }
        if (uniform) pixels.fill(uniformColor, 0, dimensions.area)
        return pixels
    }

    /**
     * Adopts a detached full-image copy only for the first unscaled, unclipped whole-viewport blit.
     * Zero-alpha pixels normalize to transparent black, exactly matching source-over onto the empty output.
     * The fresh copy becomes the output storage; this retains no source owner or second image-sized buffer.
     */
    private fun initialImagePixels(
        command: DrawCommand?,
        dimensions: PhysicalDimensions,
    ): IntArray? {
        val blit = command as? DrawCommand.BlitImage ?: return null
        if (dimensions.scale != 1 || blit.image.size != dimensions.viewport) return null
        val sourceBounds = IntRect(0, 0, dimensions.viewport.width, dimensions.viewport.height)
        if (blit.source != sourceBounds || blit.destination != dimensions.logicalBounds) return null
        return blit.image.copyArgb().also { pixels ->
            for (index in pixels.indices) if (pixels[index] ushr 24 == 0) pixels[index] = 0
        }
    }

    /**
     * Maps an ordered full-area translucent run over arbitrary destination pixels exactly.
     * Channel lookup storage is bounded to 196,864 bytes and belongs only to this invocation.
     * Every native half-up blend is preserved; partial coverage, clip changes, and other primitives end the run.
     */
    private fun composeFullFillRun(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        commands: List<DrawCommand>,
        start: Int,
        clip: IntRect?,
    ): Int {
        if (dimensions.area < 262_144) return start
        var end = start
        while (end < commands.size) {
            val fill = commands[end] as? DrawCommand.FillRectangle
            if (fill == null || fill.color.value ushr 24 == 255 || coversViewport(fill.bounds, dimensions, clip).not()) break
            end += 1
        }
        val layers = end - start
        val minimumLayers = if (dimensions.area < 1_048_576) 2 else 1
        if (layers < minimumLayers) return start
        val sources = IntArray(end - start) { (commands[start + it] as DrawCommand.FillRectangle).color.value }
        val alpha = ByteArray(256)
        val red = ByteArray(65_536)
        val green = ByteArray(65_536)
        val blue = ByteArray(65_536)
        composeFillLookup(sources, alpha, red, green, blue)
        val uniformChannels = red.all { it == red[0] } && green.all { it == green[0] } && blue.all { it == blue[0] }
        if (uniformChannels && alpha.all { it == alpha[0] }) {
            val color = ((alpha[0].toInt() and 255) shl 24) or ((red[0].toInt() and 255) shl 16) or ((green[0].toInt() and 255) shl 8) or (blue[0].toInt() and 255)
            pixels.fill(color, 0, dimensions.area)
            return end
        }
        for (position in 0 until dimensions.area) {
            val color = pixels[position]
            val initialAlpha = color ushr 24
            val offset = initialAlpha shl 8
            pixels[position] = ((alpha[initialAlpha].toInt() and 255) shl 24) or
                ((red[offset or (color ushr 16 and 255)].toInt() and 255) shl 16) or
                ((green[offset or (color ushr 8 and 255)].toInt() and 255) shl 8) or
                (blue[offset or (color and 255)].toInt() and 255)
        }
        return end
    }

    /**
     * Reuses an exactly equal prefix result before applying the remaining ordered fills.
     * Two scalar values and one validity flag belong only to this lookup construction.
     * The most opaque prefix often collapses adjacent inputs; misses retain every original rounded blend.
     */
    private fun composeFillLookup(
        sources: IntArray,
        alpha: ByteArray,
        red: ByteArray,
        green: ByteArray,
        blue: ByteArray,
    ) {
        val prefixEnd = sources.indices.maxBy { sources[it] ushr 24 } + 1
        var previousPrefix = 0
        var previousResult = 0
        var hasPrevious = false
        for (initial in 0 until 65_536) {
            val initialAlpha = initial ushr 8
            val channel = initial and 255
            var result = (initialAlpha shl 24) or (channel shl 16) or (channel shl 8) or channel
            for (index in 0 until prefixEnd) result = RasterMath.blend(sources[index], result)
            if (hasPrevious && result == previousPrefix) {
                result = previousResult
            } else {
                previousPrefix = result
                for (index in prefixEnd until sources.size) result = RasterMath.blend(sources[index], result)
                previousResult = result
                hasPrevious = true
            }
            alpha[initialAlpha] = (result ushr 24).toByte()
            red[initial] = (result ushr 16).toByte()
            green[initial] = (result ushr 8).toByte()
            blue[initial] = result.toByte()
        }
    }

    /**
     * Paints sampled pixels after the caller has materialized its uniform surface.
     */
    private fun paintSampled(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        command: DrawCommand.SampledImage,
        clip: IntRect,
    ) {
        SampledImageRasterizer.paint(pixels, dimensions.physicalSize, dimensions.scale, command, clip, dimensions.physicalOrigin)
    }

    /**
     * Pushes the clipped physical coverage of an integer logical clip without reading output pixels.
     */
    private fun pushIntegerClip(
        clips: MutableList<IntRect>,
        dimensions: PhysicalDimensions,
        bounds: IntRect,
    ) {
        val visible =
            IntRect(
                bounds.left.coerceIn(dimensions.logicalBounds.left, dimensions.logicalBounds.right),
                bounds.top.coerceIn(dimensions.logicalBounds.top, dimensions.logicalBounds.bottom),
                bounds.right.coerceIn(dimensions.logicalBounds.left, dimensions.logicalBounds.right),
                bounds.bottom.coerceIn(dimensions.logicalBounds.top, dimensions.logicalBounds.bottom),
            )
        val scale = dimensions.scale
        val physical = IntRect(visible.left * scale, visible.top * scale, visible.right * scale, visible.bottom * scale)
        clips.add(RasterMath.intersection(clips.lastOrNull() ?: dimensions.physicalBounds, physical))
    }

    /**
     * Whether the current fill and physical clip cover every output pixel at the verified density.
     */
    private fun coversViewport(
        bounds: IntRect,
        dimensions: PhysicalDimensions,
        clip: IntRect?,
    ): Boolean {
        val horizontal = bounds.left <= dimensions.logicalBounds.left && dimensions.logicalBounds.right <= bounds.right
        val vertical = bounds.top <= dimensions.logicalBounds.top && dimensions.logicalBounds.bottom <= bounds.bottom
        val unclipped = clip == null || clip == dimensions.physicalBounds
        return horizontal && vertical && unclipped
    }

    @Suppress("CyclomaticComplexMethod") // Keep clipping, opaque overwrite and the full-coverage uniformity proof together.
    private fun paintFill(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        command: DrawCommand.FillRectangle,
        clip: IntRect?,
    ): Boolean {
        val bounds = command.bounds
        val viewport = dimensions.logicalBounds
        val visible = RasterMath.intersection(viewport, clip?.let { RasterClips.logical(it, dimensions.scale) } ?: bounds, bounds)
        val left = visible.left
        val top = visible.top
        val right = visible.right
        val bottom = visible.bottom
        if (right <= left || bottom <= top) {
            return false
        }
        val source = command.color.value
        val scale = dimensions.scale
        val physicalLeft = maxOf(Math.multiplyExact(left, scale), clip?.left ?: dimensions.physicalBounds.left) - dimensions.physicalOrigin.x
        val physicalTop = maxOf(Math.multiplyExact(top, scale), clip?.top ?: dimensions.physicalBounds.top) - dimensions.physicalOrigin.y
        val physicalRight = minOf(Math.multiplyExact(right, scale), clip?.right ?: dimensions.physicalBounds.right) - dimensions.physicalOrigin.x
        val physicalBottom = minOf(Math.multiplyExact(bottom, scale), clip?.bottom ?: dimensions.physicalBounds.bottom) - dimensions.physicalOrigin.y
        if (physicalRight <= physicalLeft || physicalBottom <= physicalTop) return false
        val fullWidth = physicalLeft == 0 && physicalRight == dimensions.physicalSize.width
        val fullHeight = physicalTop == 0 && physicalBottom == dimensions.physicalSize.height
        if (source ushr 24 == 0xFF) {
            // Source-over with an opaque fill is a row overwrite, including clips between logical texels.
            for (y in physicalTop until physicalBottom) {
                val row = Math.multiplyExact(y, dimensions.physicalSize.width)
                pixels.fill(source, Math.addExact(row, physicalLeft), Math.addExact(row, physicalRight))
            }
            return fullWidth && fullHeight
        }
        val same = paintTranslucentFill(pixels, dimensions.physicalSize.width, IntRect(physicalLeft, physicalTop, physicalRight, physicalBottom), source)
        return same && fullWidth && fullHeight
    }

    /**
     * Blends a constant source over clipped physical rows with exact reuse for equal destination pixels.
     */
    private fun paintTranslucentFill(
        pixels: IntArray,
        width: Int,
        bounds: IntRect,
        source: Int,
    ): Boolean {
        // One command has a constant source. Equal destinations therefore share the exact rounded result.
        val reference = pixels[Math.addExact(Math.multiplyExact(bounds.top, width), bounds.left)]
        val blended = RasterMath.blend(source, reference)
        val area = bounds.width.toLong() * bounds.height
        if (area in 4096L..<262144L) {
            return paintPaletteFill(pixels, width, bounds, source, reference, blended)
        }
        for (y in bounds.top until bounds.bottom) {
            val row = Math.multiplyExact(y, width)
            for (index in Math.addExact(row, bounds.left) until Math.addExact(row, bounds.right)) {
                val destination = pixels[index]
                pixels[index] = if (destination == reference) blended else RasterMath.blend(source, destination)
            }
        }
        return false
    }

    /**
     * Reuses exact destination colors in a command-local 64-entry direct-mapped table.
     * The constant source belongs to this call; a hash collision replaces its slot after an exact ARGB check.
     * Two arrays and a validity mask are released on return and never retain image or frame owners.
     */
    private fun paintPaletteFill(
        pixels: IntArray,
        width: Int,
        bounds: IntRect,
        source: Int,
        reference: Int,
        blended: Int,
    ): Boolean {
        val destinations = IntArray(64)
        val results = IntArray(64)
        var valid = 0L
        var uniform = true
        for (y in bounds.top until bounds.bottom) {
            val row = y * width
            for (index in (row + bounds.left) until (row + bounds.right)) {
                val destination = pixels[index]
                if (destination == reference) {
                    pixels[index] = blended
                    continue
                }
                // Modular multiplication is intentional: only the top six hash bits select the slot.
                val slot = (destination * -1640531527) ushr 26
                val bit = 1L shl slot
                if (valid and bit == 0L || destinations[slot] != destination) {
                    destinations[slot] = destination
                    results[slot] = RasterMath.blend(source, destination)
                    valid = valid or bit
                }
                pixels[index] = results[slot]
                if (uniform && results[slot] != blended) uniform = false
            }
        }
        return uniform
    }

    private fun paintBlit(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        command: DrawCommand.BlitImage,
        clip: IntRect?,
    ) {
        val bounds = command.destination
        val viewport = dimensions.logicalBounds
        val visible = RasterMath.intersection(viewport, clip?.let { RasterClips.logical(it, dimensions.scale) } ?: bounds, bounds)
        val left = visible.left
        val top = visible.top
        val right = visible.right
        val bottom = visible.bottom
        if (right <= left || bottom <= top) {
            return
        }
        if (command.source.width == 1 && command.source.height == 1) {
            val color = ArgbColor(command.image.argbAt(command.source.left, command.source.top))
            paintFill(pixels, dimensions, DrawCommand.FillRectangle(bounds, color), clip)
            return
        }
        if (dimensions.scale == 1 && command.source.width == bounds.width && command.source.height == bounds.height) {
            paintUnscaledIdentityBlit(pixels, dimensions.physicalSize.width, command, visible, dimensions.origin)
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
                paintLogicalPixel(pixels, dimensions, logicalX, logicalY, sourceColor, clip)
            }
        }
    }

    /**
     * Copies an unscaled matching source extent with exact blending and no nearest-coordinate divisions.
     * The caller resolves physical clipping; validated image and output extents bound every index.
     */
    private fun paintUnscaledIdentityBlit(
        pixels: IntArray,
        width: Int,
        command: DrawCommand.BlitImage,
        visible: IntRect,
        origin: IntOffset,
    ) {
        for (y in visible.top until visible.bottom) {
            val sourceY = command.source.top + (y - command.destination.top)
            val row = (y - origin.y) * width
            for (x in visible.left until visible.right) {
                val sourceX = command.source.left + (x - command.destination.left)
                val index = row + x - origin.x
                pixels[index] = RasterMath.blend(command.image.argbAt(sourceX, sourceY), pixels[index])
            }
        }
    }

    private fun paintLogicalPixel(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        logicalX: Int,
        logicalY: Int,
        source: Int,
        clip: IntRect?,
    ) {
        val physicalX = Math.multiplyExact(logicalX, dimensions.scale)
        val physicalY = Math.multiplyExact(logicalY, dimensions.scale)
        val localX = physicalX - dimensions.physicalOrigin.x
        val localY = physicalY - dimensions.physicalOrigin.y
        val firstIndex = Math.addExact(Math.multiplyExact(localY, dimensions.physicalSize.width), localX)
        val firstDestination = pixels[firstIndex]
        val firstColor = RasterMath.blend(source, firstDestination)
        val left = maxOf(physicalX, clip?.left ?: physicalX)
        val top = maxOf(physicalY, clip?.top ?: physicalY)
        val right = minOf(physicalX + dimensions.scale, clip?.right ?: (physicalX + dimensions.scale))
        val bottom = minOf(physicalY + dimensions.scale, clip?.bottom ?: (physicalY + dimensions.scale))
        for (dy in (top - physicalY) until (bottom - physicalY)) {
            val row = Math.multiplyExact(Math.addExact(localY, dy), dimensions.physicalSize.width)
            val first = Math.addExact(row, localX)
            for (dx in (left - physicalX) until (right - physicalX)) {
                val index = Math.addExact(first, dx)
                val destination = pixels[index]
                pixels[index] = if (destination == firstDestination) firstColor else RasterMath.blend(source, destination)
            }
        }
    }

    private fun paintBlitPixels(
        pixels: IntArray,
        dimensions: PhysicalDimensions,
        command: DrawCommand.BlitImagePixels,
        clip: IntRect?,
    ) {
        val bounds = command.destination
        val viewport = dimensions.logicalBounds
        val visible = RasterMath.intersection(viewport, clip?.let { RasterClips.logical(it, dimensions.scale) } ?: bounds, bounds)
        if (visible.width == 0 || visible.height == 0) return
        if (command.source.width == 1 && command.source.height == 1) {
            val color = ArgbColor(command.image.argbAt(command.source.left, command.source.top))
            paintFill(pixels, dimensions, DrawCommand.FillRectangle(bounds, color), clip)
            return
        }
        val scale = dimensions.scale
        val horizontal = PixelAxis(command.source.left, command.source.width, bounds.left, bounds.width, scale)
        val vertical = PixelAxis(command.source.top, command.source.height, bounds.top, bounds.height, scale)
        val left = maxOf(Math.multiplyExact(visible.left, scale), clip?.left ?: dimensions.physicalBounds.left)
        val right = minOf(Math.multiplyExact(visible.right, scale), clip?.right ?: dimensions.physicalBounds.right)
        val top = maxOf(Math.multiplyExact(visible.top, scale), clip?.top ?: dimensions.physicalBounds.top)
        val bottom = minOf(Math.multiplyExact(visible.bottom, scale), clip?.bottom ?: dimensions.physicalBounds.bottom)
        for (y in top until bottom) {
            val sourceY = vertical.sourceAt(y)
            val row = Math.multiplyExact(y - dimensions.physicalOrigin.y, dimensions.physicalSize.width)
            for (x in left until right) {
                val sourceColor = command.image.argbAt(horizontal.sourceAt(x), sourceY)
                val index = Math.addExact(row, x - dimensions.physicalOrigin.x)
                pixels[index] = RasterMath.blend(sourceColor, pixels[index])
            }
        }
    }

    private class PixelAxis(
        private val sourceStart: Int,
        sourceExtent: Int,
        destinationStart: Int,
        destinationExtent: Int,
        scale: Int,
    ) {
        private val sourceExtent: Long = sourceExtent.toLong()
        private val destinationOrigin: Long = destinationStart.toLong() * scale
        private val denominator: Long = destinationExtent.toLong() * scale * 2L

        fun sourceAt(position: Int): Int {
            val center = (position.toLong() - destinationOrigin) * 2L + 1L
            val offset =
                if (center <= Long.MAX_VALUE / sourceExtent) {
                    center * sourceExtent / denominator
                } else {
                    // A mostly offscreen logical destination may have a scaled extent larger than Int.MAX_VALUE.
                    BigInteger
                        .valueOf(center)
                        .multiply(BigInteger.valueOf(sourceExtent))
                        .divide(BigInteger.valueOf(denominator))
                        .longValueExact()
                }
            return Math.toIntExact(sourceStart.toLong() + offset)
        }
    }

    private fun checkedDimensions(
        viewport: IntSize,
        scale: Int,
        origin: IntOffset = IntOffset.Zero,
    ): PhysicalDimensions {
        require(0 < viewport.width) { "Viewport width must be positive." }
        require(0 < viewport.height) { "Viewport height must be positive." }
        require(0 < scale) { "Pixel scale must be positive." }
        val physicalWidth = checkedMultiply(viewport.width, scale, "Physical width")
        val physicalHeight = checkedMultiply(viewport.height, scale, "Physical height")
        val area = checkedMultiply(physicalWidth, physicalHeight, "Physical pixel area")
        val physicalOrigin = if (origin == IntOffset.Zero) IntOffset.Zero else IntOffset(Math.multiplyExact(origin.x, scale), Math.multiplyExact(origin.y, scale))
        return PhysicalDimensions(viewport, IntSize(physicalWidth, physicalHeight), scale, area, origin, physicalOrigin)
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
            if (sourceAlpha == 0xFF) return source
            val destinationAlpha = destination ushr 24 and 0xFF
            // Transparent output is canonical black; other zero-alpha cases cancel the exact blend equation.
            if (sourceAlpha == 0) return if (destinationAlpha == 0) 0 else destination
            if (destinationAlpha == 0) return source
            val alphaNumerator = sourceAlpha * 255 + destinationAlpha * (255 - sourceAlpha)
            val outputAlpha = (alphaNumerator + 127) / 255
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
            return (outputAlpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        private fun channel(
            source: Int,
            destination: Int,
            sourceAlpha: Int,
            destinationAlpha: Int,
            alphaNumerator: Int,
        ): Int {
            // Both weights sum to at most 65,025; the rounded channel numerator remains below 16,613,888.
            val numerator =
                source * sourceAlpha * 255 + destination * destinationAlpha * (255 - sourceAlpha)
            return (numerator + alphaNumerator / 2) / alphaNumerator
        }
    }

    private class ImageImpl(
        override val size: IntSize,
        private val pixels: IntArray,
    ) : HeadlessImage {
        override fun argbAt(
            x: Int,
            y: Int,
        ): Int {
            require(0 <= x && x < size.width) { "X coordinate must be inside the image." }
            require(0 <= y && y < size.height) { "Y coordinate must be inside the image." }
            return pixels[y * size.width + x]
        }

        override fun copyArgb(): IntArray = pixels.copyOf()

        override fun encodePng(): ByteArray = PngEncoder.encode(size, pixels)
    }

    private class FrameImpl(
        override val viewport: IntSize,
        override val pixelScale: Int,
        override val image: HeadlessImage,
        override val semantics: List<SemanticsEntry>,
    ) : HeadlessFrame

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

        fun encode(
            size: IntSize,
            pixels: IntArray,
        ): ByteArray {
            val scanlines = scanlines(size, pixels)
            val compressed = zlib(scanlines)
            val ihdr =
                ByteBuffer
                    .allocate(13)
                    .putInt(size.width)
                    .putInt(size.height)
                    .put(8)
                    .put(6)
                    .array()
            val output = ByteBuffer.allocate(sizeBytes(ihdr, compressed))
            output.put(signature)
            output.writeChunk("IHDR", ihdr)
            output.writeChunk("IDAT", compressed)
            output.writeChunk("IEND", ByteArray(0))
            return output.array()
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
            val output = ByteBuffer.allocate(checkedAdd(deflateBytes, 6, "PNG zlib stream"))
            output.put(0x78).put(0x01).order(ByteOrder.LITTLE_ENDIAN)
            var source = 0
            repeat(blockCount) { blockIndex ->
                val blockLength = minOf(data.size - source, MAX_STORED_BLOCK_LENGTH)
                output.put(if (blockIndex == blockCount - 1) 0x01 else 0x00)
                output.putShort(blockLength.toShort())
                output.putShort(blockLength.inv().toShort())
                output.put(data, source, blockLength)
                source += blockLength
            }
            val adler = Adler32().apply { update(data) }.value.toInt()
            output.order(ByteOrder.BIG_ENDIAN).putInt(adler)
            return output.array()
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

        private fun ByteBuffer.writeChunk(
            type: String,
            payload: ByteArray,
        ) {
            val typeBytes = type.encodeToByteArray()
            putInt(payload.size)
            put(typeBytes)
            put(payload)
            val crc =
                CRC32()
                    .apply {
                        update(typeBytes)
                        update(payload)
                    }.value
                    .toInt()
            putInt(crc)
        }
    }

    private data class PhysicalDimensions(
        val viewport: IntSize,
        val physicalSize: IntSize,
        val scale: Int,
        val area: Int,
        val origin: IntOffset,
        val physicalOrigin: IntOffset,
    ) {
        val logicalBounds = IntRect(origin.x, origin.y, Math.addExact(origin.x, viewport.width), Math.addExact(origin.y, viewport.height))
        val physicalBounds = if (scale == 1) logicalBounds else IntRect(physicalOrigin.x, physicalOrigin.y, Math.addExact(physicalOrigin.x, physicalSize.width), Math.addExact(physicalOrigin.y, physicalSize.height))
    }
}
