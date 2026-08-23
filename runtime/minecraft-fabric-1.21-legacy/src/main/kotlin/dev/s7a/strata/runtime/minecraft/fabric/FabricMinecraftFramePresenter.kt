package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.DynamicTexture
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns prepared display layers, native frame textures, and render-work counters for one Fabric screen.
 *
 * Every operation is confined to the owning Minecraft client thread.
 * Portable command runs are rasterized into bounded native textures, while platform commands are submitted through a borrowed callback that is never retained.
 * Releasing presentation state clears display-list and pointer references and unregisters every owned texture; cleanup failures are aggregated with the first failure kept primary.
 * The presenter is reusable after release so a transiently removed screen can later attach again.
 *
 * @param minecraftClient client whose render thread and texture manager own this presentation.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
internal class FabricMinecraftFramePresenter(
    private val minecraftClient: Minecraft,
) {
    private val textures: MutableList<DynamicTexture> = ArrayList()
    private val textureLocations: MutableList<MinecraftResourceLocation> = ArrayList()
    private var preparedCommands: List<DrawCommand>? = null
    private var preparedViewport: IntSize? = null
    private var preparedLayers: List<FrameLayer> = emptyList()
    private var pointerPosition: IntOffset? = null
    private var pointerFrameCommands: List<DrawCommand>? = null
    private var renderExtractionCount: Long = 0L
    private var hostFrameCount: Long = 0L
    private var extractedPointerDispatchCount: Long = 0L
    private var framePreparationCount: Long = 0L
    private var portableRasterizationCount: Long = 0L
    private var textureUploadCount: Long = 0L

    /**
     * Records one native render extraction before common frame work begins.
     */
    internal fun recordRenderExtraction() {
        requireClientThread()
        renderExtractionCount += 1L
    }

    /**
     * Records one request for a committed common host frame.
     */
    internal fun recordHostFrame() {
        requireClientThread()
        hostFrameCount += 1L
    }

    /**
     * Determines whether the extracted frame requires a native pointer move.
     *
     * @param position current native pointer position.
     * @param commands committed display-list identity associated with the position.
     * @return true when either the position or display-list identity changed.
     */
    internal fun needsPointerDispatch(
        position: IntOffset,
        commands: List<DrawCommand>,
    ): Boolean {
        requireClientThread()
        return position != pointerPosition || commands !== pointerFrameCommands
    }

    /**
     * Associates an extracted pointer dispatch with the committed display list and records its cost.
     *
     * @param position dispatched native pointer position.
     * @param commands committed display list observed before dispatch.
     */
    internal fun recordExtractedPointerDispatch(
        position: IntOffset,
        commands: List<DrawCommand>,
    ) {
        requireClientThread()
        extractedPointerDispatchCount += 1L
        pointerPosition = position
        pointerFrameCommands = commands
    }

    /**
     * Associates an independently delivered native pointer move with the currently prepared display list.
     *
     * @param position dispatched native pointer position.
     */
    internal fun recordPointerInput(position: IntOffset) {
        requireClientThread()
        pointerPosition = position
        pointerFrameCommands = preparedCommands
    }

    /**
     * Clears pointer identity before a common host is attached for a new visible session.
     */
    internal fun resetPointer() {
        requireClientThread()
        pointerPosition = null
        pointerFrameCommands = null
    }

    /**
     * Presents one committed display list through bounded portable textures and ordered platform submissions.
     *
     * The callback is borrowed only for this call and is invoked under the exact clip active at the platform command's display-list position.
     *
     * @param graphics native GUI rendering target.
     * @param commands committed display list.
     * @param viewport committed common viewport.
     * @param platformRenderer borrowed native renderer for a platform command.
     * @throws Throwable when partitioning, rasterization, texture upload, native blitting, or the borrowed renderer fails.
     */
    internal fun present(
        graphics: GuiGraphics,
        commands: List<DrawCommand>,
        viewport: IntSize,
        platformRenderer: (GuiGraphics, DrawCommand.Platform) -> Unit,
    ) {
        requireClientThread()
        val reusePreparedFrame = commands === preparedCommands && viewport == preparedViewport
        val layers =
            if (reusePreparedFrame) {
                preparedLayers
            } else {
                framePreparationCount += 1L
                partitionFrame(commands, viewport)
            }
        val previousPortableLayers =
            if (reusePreparedFrame || viewport != preparedViewport) {
                emptyList()
            } else {
                preparedLayers.filterIsInstance<PortableLayer>()
            }
        var textureIndex = 0
        layers.forEach { layer ->
            when (layer) {
                is PortableLayer -> {
                    textureIndex =
                        presentPortableLayer(
                            graphics,
                            layer,
                            textureIndex,
                            reusePreparedFrame,
                            previousPortableLayers,
                        )
                }

                is PlatformLayer -> {
                    presentPlatformLayer(graphics, layer, viewport, platformRenderer)
                }
            }
        }
        if (reusePreparedFrame.not()) {
            trimTextures(textureIndex)
            preparedCommands = commands
            preparedViewport = viewport
            preparedLayers = layers
        }
    }

    /**
     * Releases every transient display-list, pointer, and native texture resource owned by this presenter.
     *
     * @throws Throwable when Minecraft rejects one or more texture releases; later failures are suppressed onto the first.
     */
    internal fun release() {
        requireClientThread()
        preparedCommands = null
        preparedViewport = null
        preparedLayers = emptyList()
        pointerPosition = null
        pointerFrameCommands = null
        trimTextures(0)
    }

    private fun presentPortableLayer(
        graphics: GuiGraphics,
        layer: PortableLayer,
        textureIndex: Int,
        reusePreparedFrame: Boolean,
        previousPortableLayers: List<PortableLayer>,
    ): Int {
        val retainedTexture = textures.getOrNull(textureIndex)
        val previousPortableLayer = previousPortableLayers.getOrNull(textureIndex)
        val reusePortableLayer =
            reusePreparedFrame ||
                (
                    retainedTexture != null &&
                        previousPortableLayer != null &&
                        previousPortableLayer.commands == layer.commands &&
                        previousPortableLayer.bounds.size == layer.bounds.size
                )
        if (reusePortableLayer) {
            checkNotNull(retainedTexture) {
                "A cached portable frame layer has no retained texture."
            }
        } else {
            portableRasterizationCount += 1L
            upload(textureIndex, rasterizeHeadless(layer.commands, layer.bounds.size))
        }
        val location =
            checkNotNull(textureLocations.getOrNull(textureIndex)) {
                "A prepared portable frame layer has no registered texture location."
            }
        FabricMinecraftTextureBlitter.blit(
            graphics,
            location,
            layer.bounds.left,
            layer.bounds.top,
            layer.bounds.width,
            layer.bounds.height,
        )
        return textureIndex + 1
    }

    private fun presentPlatformLayer(
        graphics: GuiGraphics,
        layer: PlatformLayer,
        viewport: IntSize,
        platformRenderer: (GuiGraphics, DrawCommand.Platform) -> Unit,
    ) {
        val visible = intersection(IntRect(0, 0, viewport.width, viewport.height), layer.command.bounds)
        if (visible.width <= 0 || visible.height <= 0) return
        val clip = layer.clip
        if (clip != null) graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
        try {
            platformRenderer(graphics, layer.command)
        } finally {
            if (clip != null) graphics.disableScissor()
        }
    }

    private fun partitionFrame(
        commands: List<DrawCommand>,
        viewport: IntSize,
    ): List<FrameLayer> {
        val layers = ArrayList<FrameLayer>()
        val activeClips = ArrayList<IntRect>()
        val viewportBounds = IntRect(0, 0, viewport.width, viewport.height)
        val portable = ArrayList<DrawCommand>()
        var portableBounds: IntRect? = null

        fun flushPortable() {
            val bounds = portableBounds
            if (bounds != null) {
                repeat(activeClips.size) { portable.add(DrawCommand.PopClip) }
                layers.add(PortableLayer(localizePortable(portable, bounds), bounds))
            }
            portable.clear()
            activeClips.forEach { clip -> portable.add(DrawCommand.PushClip(clip)) }
            portableBounds = null
        }
        commands.forEach { command ->
            when (command) {
                is DrawCommand.FillRectangle -> {
                    portable.add(command)
                    portableBounds = includeVisibleBounds(portableBounds, command.bounds, activeClips, viewportBounds)
                }

                is DrawCommand.BlitImage -> {
                    portable.add(command)
                    portableBounds = includeVisibleBounds(portableBounds, command.destination, activeClips, viewportBounds)
                }

                is DrawCommand.PushClip -> {
                    activeClips.add(command.bounds)
                    portable.add(command)
                }

                DrawCommand.PopClip -> {
                    require(activeClips.isNotEmpty()) { "Clip pop has no matching push." }
                    activeClips.removeAt(activeClips.lastIndex)
                    portable.add(command)
                }

                is DrawCommand.Platform -> {
                    flushPortable()
                    val clip = activeClips.fold(viewportBounds, ::intersection)
                    layers.add(PlatformLayer(command, clip.takeIf { activeClips.isNotEmpty() }))
                }
            }
        }
        require(activeClips.isEmpty()) { "Clip push has no matching pop." }
        flushPortable()
        return layers
    }

    private fun includeVisibleBounds(
        accumulated: IntRect?,
        commandBounds: IntRect,
        activeClips: List<IntRect>,
        viewportBounds: IntRect,
    ): IntRect? {
        val visible = activeClips.fold(intersection(viewportBounds, commandBounds), ::intersection)
        if (visible.width <= 0 || visible.height <= 0) return accumulated
        val previous = accumulated ?: return visible
        return IntRect(
            minOf(previous.left, visible.left),
            minOf(previous.top, visible.top),
            maxOf(previous.right, visible.right),
            maxOf(previous.bottom, visible.bottom),
        )
    }

    private fun localizePortable(
        commands: List<DrawCommand>,
        bounds: IntRect,
    ): List<DrawCommand> {
        val offset = IntOffset(-bounds.left, -bounds.top)
        return commands.mapNotNull { command ->
            when (command) {
                is DrawCommand.FillRectangle -> {
                    val visible = intersection(command.bounds, bounds)
                    if (visible.width <= 0 || visible.height <= 0) null else DrawCommand.FillRectangle(visible + offset, command.color)
                }

                is DrawCommand.BlitImage -> {
                    val visible = intersection(command.destination, bounds)
                    if (visible.width <= 0 || visible.height <= 0) null else DrawCommand.BlitImage(command.image, command.source, command.destination + offset)
                }

                is DrawCommand.PushClip -> {
                    DrawCommand.PushClip(intersection(command.bounds, bounds) + offset)
                }

                DrawCommand.PopClip -> {
                    DrawCommand.PopClip
                }

                is DrawCommand.Platform -> {
                    error("Portable layers cannot contain platform commands.")
                }
            }
        }
    }

    private fun trimTextures(retainedCount: Int) {
        var failure: Throwable? = null
        while (retainedCount < textures.size) {
            textures.removeAt(textures.lastIndex)
            val location = textureLocations.removeAt(textureLocations.lastIndex)
            try {
                minecraftClient.textureManager.release(location)
            } catch (caught: Throwable) {
                val primary = failure
                if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
            }
        }
        failure?.let { throw it }
    }

    private fun upload(
        index: Int,
        image: HeadlessImage,
    ): DynamicTexture {
        val current = textures.getOrNull(index)
        val currentPixels = current?.pixels
        val needsResize = currentPixels == null || currentPixels.width != image.size.width || currentPixels.height != image.size.height
        if (needsResize.not()) {
            fillTexture(current, image)
            return current
        }
        val native = NativeImage(image.size.width, image.size.height, false)
        try {
            fillPixels(native, image)
        } catch (failure: Throwable) {
            closeAfterFailure(native, failure)
        }
        val replacement =
            try {
                createFabricMinecraftDynamicTexture(native)
            } catch (failure: Throwable) {
                closeAfterFailure(native, failure)
            }
        textureUploadCount += 1L
        val location = textureLocations.getOrNull(index) ?: nextTextureLocation()
        try {
            minecraftClient.textureManager.register(location, replacement)
        } catch (failure: Throwable) {
            closeAfterFailure(replacement, failure)
        }
        if (current == null) {
            textures.add(replacement)
            textureLocations.add(location)
        } else {
            textures[index] = replacement
        }
        return replacement
    }

    private fun closeAfterFailure(
        resource: AutoCloseable,
        failure: Throwable,
    ): Nothing {
        try {
            resource.close()
        } catch (cleanup: Throwable) {
            FabricMinecraftFailures.addSuppressed(failure, cleanup)
        }
        throw failure
    }

    private fun fillTexture(
        target: DynamicTexture,
        image: HeadlessImage,
    ) {
        val native = checkNotNull(target.pixels) { "A retained frame texture was already released." }
        fillPixels(native, image)
        target.upload()
        textureUploadCount += 1L
    }

    private fun fillPixels(
        native: NativeImage,
        image: HeadlessImage,
    ) {
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) {
                native.setPixel(x, y, image.argbAt(x, y))
            }
        }
    }

    private fun intersection(
        first: IntRect,
        second: IntRect,
    ): IntRect {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = maxOf(left, minOf(first.right, second.right))
        val bottom = maxOf(top, minOf(first.bottom, second.bottom))
        return IntRect(left, top, right, bottom)
    }

    private fun requireClientThread() {
        check(minecraftClient.isSameThread) { "Fabric Minecraft frame presentation is confined to the client thread." }
    }

    private sealed interface FrameLayer

    private data class PortableLayer(
        val commands: List<DrawCommand>,
        val bounds: IntRect,
    ) : FrameLayer

    private data class PlatformLayer(
        val command: DrawCommand.Platform,
        val clip: IntRect?,
    ) : FrameLayer

    private companion object {
        private val textureSequence = AtomicLong()

        private fun nextTextureLocation(): MinecraftResourceLocation =
            MinecraftResourceLocation.fromNamespaceAndPath(
                "strata",
                "runtime/frame/${textureSequence.getAndIncrement().toULong()}",
            )
    }
}
