package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * Owns prepared display layers, native frame textures, and render-work counters for one Fabric screen.
 *
 * Every operation is confined to the owning Minecraft client thread.
 * Portable command runs are rasterized into bounded native textures, while platform commands are submitted through a borrowed callback that is never retained.
 * Releasing presentation state clears screen-owned drawing, pointer, and texture references before device-owned retirement; queued textures survive their actual GUI-consumption fences.
 * The presenter is reusable after release so a transiently removed screen can later attach again.
 *
 * @param minecraftClient client whose render thread and texture manager own this presentation.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions")
internal class FabricMinecraftFramePresenter(
    private val minecraftClient: Minecraft,
) {
    private val portableFrames = FabricMinecraftPortableFrames()
    private var preparedCommands: List<DrawCommand>? = null
    private var preparedViewport: IntSize? = null
    private var preparedScale: Int? = null
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
        // The shared target releases expose GUI scale as either Double or Int.
        val nativeScale: Number = minecraftClient.window.guiScale
        val scale = nativeScale.toInt()
        require(0 < scale) { "GUI scale must be positive." }
        val generation = portableFrames.releaseGeneration
        val reusePreparedFrame = commands === preparedCommands && viewport == preparedViewport && scale == preparedScale
        val layers =
            if (reusePreparedFrame) {
                preparedLayers
            } else {
                framePreparationCount += 1L
                partitionFrame(commands, viewport)
            }
        val images = layers.filterIsInstance<PortableLayer>().map { FabricMinecraftPortableImage(it.commands, it.bounds.size, scale) }
        portableFrames.present(
            images,
            { portableRasterizationCount += 1L },
            { textureUploadCount += 1L },
        ) { textures, queued ->
            var textureIndex = 0
            layers.forEach { layer ->
                when (layer) {
                    is PortableLayer -> {
                        val texture = textures[textureIndex++]
                        queued()
                        FabricMinecraftTextureBlitter.blit(
                            graphics,
                            texture.location,
                            layer.bounds.left,
                            layer.bounds.top,
                            layer.bounds.width,
                            layer.bounds.height,
                            Math.multiplyExact(layer.bounds.width, scale),
                            Math.multiplyExact(layer.bounds.height, scale),
                        )
                    }

                    is PlatformLayer -> {
                        presentPlatformLayer(graphics, layer, viewport, platformRenderer)
                    }
                }
            }
        }
        if (reusePreparedFrame.not() && portableFrames.releaseGeneration == generation) {
            preparedCommands = commands
            preparedViewport = viewport
            preparedScale = scale
            preparedLayers = layers
        }
    }

    /**
     * Drops every transient display-list, pointer, and texture reference owned by this presenter.
     *
     * Pending native textures retire independently after their final GUI-consumption fence.
     * @throws Throwable when eligible device-owned retirement fails after all presenter references have been cleared.
     */
    internal fun release() {
        requireClientThread()
        preparedCommands = null
        preparedViewport = null
        preparedScale = null
        preparedLayers = emptyList()
        pointerPosition = null
        pointerFrameCommands = null
        portableFrames.release()
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
        FabricMinecraftFailures.runWithCleanup(
            { platformRenderer(graphics, layer.command) },
            { if (clip != null) graphics.disableScissor() },
        )
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

                is DrawCommand.SampledImage -> {
                    portable.add(command)
                    val clip = activeClips.fold(viewportBounds, ::intersection)
                    val bounds = command.destination.enclosingFabricViewportBounds(clip)
                    if (bounds != null) {
                        portableBounds = includeVisibleBounds(portableBounds, bounds, activeClips, viewportBounds)
                    }
                }

                is DrawCommand.BlitImagePixels -> {
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

                is DrawCommand.SampledImage -> {
                    val destination = command.destination
                    command.copy(destination = FloatRect(destination.left + offset.x, destination.top + offset.y, destination.right + offset.x, destination.bottom + offset.y))
                }

                is DrawCommand.BlitImagePixels -> {
                    val visible = intersection(command.destination, bounds)
                    if (visible.width <= 0 || visible.height <= 0) null else command.copy(destination = command.destination + offset)
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
}
