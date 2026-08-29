package dev.s7a.strata.runtime.minecraft.fabric

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
    private val sampledImages = FabricMinecraftSampledImageCache()
    private var preparedCommands: List<DrawCommand>? = null
    private var preparedViewport: IntSize? = null
    private var preparedScale: Int? = null
    private var preparedLayers: List<FabricMinecraftFrameLayer> = emptyList()
    private var pointerPosition: IntOffset? = null
    private var pointerFrameCommands: List<DrawCommand>? = null
    private var renderExtractionCount: Long = 0L
    private var hostFrameCount: Long = 0L
    private var extractedPointerDispatchCount: Long = 0L
    private var framePreparationCount: Long = 0L
    private var portableRasterizationCount: Long = 0L
    private var textureUploadCount: Long = 0L
    private var sampledImageDirectHitCount: Long = 0L
    private var sampledImageDirectMissCount: Long = 0L
    private var sampledImageUploadCount: Long = 0L
    private var sampledImageDrawCount: Long = 0L
    private var sampledImageEvictionCount: Long = 0L
    private var sampledImageIneligibleFallbackCount: Long = 0L
    private var sampledImageCapacityFallbackCount: Long = 0L
    private var sampledImageRetainedEntryCount: Long = 0L
    private var sampledImageRetainedByteCount: Long = 0L

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
                partitionFabricMinecraftFrame(commands, viewport)
            }
        val sampled = layers.filterIsInstance<FabricMinecraftFrameLayer.Sampled>().map { it.command.image }
        try {
            sampledImages.present(
                sampled,
                { sampledImageDirectHitCount += 1L },
                { sampledImageDirectMissCount += 1L },
                { sampledImageUploadCount += 1L },
                { sampledImageEvictionCount += 1L },
            ) { textureFor, sampledQueued ->
                val resolved =
                    layers.map { layer ->
                        if (layer is FabricMinecraftFrameLayer.Sampled && textureFor(layer.command.image) == null) {
                            if (sampledImages.supports(layer.command.image)) {
                                sampledImageCapacityFallbackCount += 1L
                            } else {
                                sampledImageIneligibleFallbackCount += 1L
                            }
                            portableFabricSampledFallback(layer)
                        } else {
                            layer
                        }
                    }
                resolved.filterIsInstance<FabricMinecraftFrameLayer.Portable>().forEach { layer ->
                    sampledImageIneligibleFallbackCount = Math.addExact(sampledImageIneligibleFallbackCount, layer.ineligibleSampledImages.toLong())
                }
                val images = resolved.filterIsInstance<FabricMinecraftFrameLayer.Portable>().map { FabricMinecraftPortableImage(it.commands, it.bounds.size, scale) }
                portableFrames.present(
                    images,
                    { portableRasterizationCount += 1L },
                    { textureUploadCount += 1L },
                ) { textures, portableQueued ->
                    var textureIndex = 0
                    resolved.forEach { layer ->
                        when (layer) {
                            is FabricMinecraftFrameLayer.Portable -> {
                                val texture = textures[textureIndex++]
                                portableQueued()
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

                            is FabricMinecraftFrameLayer.Sampled -> {
                                val texture = checkNotNull(textureFor(layer.command.image))
                                sampledQueued(layer.command.image)
                                sampledImageDrawCount += 1L
                                presentSampledLayer(graphics, layer, texture)
                            }

                            is FabricMinecraftFrameLayer.Platform -> {
                                presentPlatformLayer(graphics, layer, viewport, platformRenderer)
                            }
                        }
                    }
                }
            }
        } finally {
            sampledImageRetainedEntryCount = sampledImages.retainedEntryCount().toLong()
            sampledImageRetainedByteCount = sampledImages.retainedByteCount()
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
        FabricMinecraftFailures.runWithCleanup(portableFrames::release, sampledImages::release)
    }

    private fun presentSampledLayer(
        graphics: GuiGraphics,
        layer: FabricMinecraftFrameLayer.Sampled,
        texture: FabricMinecraftPortableTexture,
    ) {
        val clip = layer.clip
        if (clip != null) graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
        FabricMinecraftFailures.runWithCleanup(
            { drawFabricMinecraftSampledImage(graphics, texture, layer.command) },
            { if (clip != null) graphics.disableScissor() },
        )
    }

    private fun presentPlatformLayer(
        graphics: GuiGraphics,
        layer: FabricMinecraftFrameLayer.Platform,
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
}
