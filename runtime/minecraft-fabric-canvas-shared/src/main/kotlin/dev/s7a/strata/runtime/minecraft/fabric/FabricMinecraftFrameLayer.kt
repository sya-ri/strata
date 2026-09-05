@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.floor

/**
 * One ordered native presentation layer produced from a committed portable display list.
 *
 * Portable commands retain tight localized bounds, eligible sampled images retain their immutable source identity and floating destination, and platform payloads remain opaque.
 */
internal sealed interface FabricMinecraftFrameLayer {
    /**
     * A tight CPU-rasterized fallback run in coordinates local to [bounds].
     */
    class Portable(
        @get:JvmSynthetic
        internal val commands: List<DrawCommand>,
        @get:JvmSynthetic
        internal val bounds: IntRect,
        @get:JvmSynthetic
        internal val ineligibleSampledImages: Int = 0,
    ) : FabricMinecraftFrameLayer

    /**
     * One direct immutable sampled image with the clip active at its exact display-list position.
     */
    class Sampled(
        @get:JvmSynthetic
        internal val command: DrawCommand.SampledImage,
        @get:JvmSynthetic
        internal val clip: IntRect?,
        @get:JvmSynthetic
        internal val visibleBounds: IntRect,
    ) : FabricMinecraftFrameLayer

    /**
     * One opaque native payload with the clip active at its exact display-list position.
     */
    class Platform(
        @get:JvmSynthetic
        internal val command: DrawCommand.Platform,
        @get:JvmSynthetic
        internal val clip: IntRect?,
    ) : FabricMinecraftFrameLayer
}

/**
 * Submits resolved frame layers in display-list order with one native ordering boundary between every adjacent pair.
 *
 * Empty and singleton lists create no boundary. The callbacks are borrowed synchronously, invoked on the caller's thread, and never retained.
 * An exception from either callback is propagated unchanged, and no later callback is invoked.
 *
 * @param layers resolved layers in exact display-list order.
 * @param advance advances the native renderer to the next ordering boundary.
 * @param submit submits one layer without advancing before the first or after the last layer.
 */
@JvmSynthetic
internal inline fun submitFabricMinecraftFrameLayers(
    layers: List<FabricMinecraftFrameLayer>,
    advance: () -> Unit,
    submit: (FabricMinecraftFrameLayer) -> Unit,
) {
    layers.forEachIndexed { index, layer ->
        if (0 < index) advance()
        submit(layer)
    }
}

/**
 * Partitions one committed frame into tight portable runs, independently cacheable sampled images, and platform barriers.
 *
 * Direct eligibility is deliberately limited to ordinary orientation, opaque-white tint, zero alpha cutoff, and integer source texel edges.
 * Unsupported sampled commands remain inside the existing portable path without changing their pixels or ordering.
 *
 * @param commands complete balanced display list.
 * @param viewport positive or empty logical viewport used only for visibility and bounded fallback allocation.
 * @return immutable layers in exact display-list order.
 */
@JvmSynthetic
@Suppress("CyclomaticComplexMethod")
internal fun partitionFabricMinecraftFrame(
    commands: List<DrawCommand>,
    viewport: IntSize,
): List<FabricMinecraftFrameLayer> {
    val layers = ArrayList<FabricMinecraftFrameLayer>()
    val activeClips = ArrayList<IntRect>()
    val viewportBounds = IntRect(0, 0, viewport.width, viewport.height)
    var portable = ArrayList<DrawCommand>()
    var portableBounds: IntRect? = null
    var portableIneligibleSampledImages = 0

    fun flushPortable() {
        val bounds = portableBounds
        if (bounds != null) {
            repeat(activeClips.size) { portable.add(DrawCommand.PopClip) }
            layers.add(FabricMinecraftFrameLayer.Portable(localizeFabricPortable(portable, bounds), bounds, portableIneligibleSampledImages))
        }
        portable = ArrayList()
        activeClips.forEach { clip -> portable.add(DrawCommand.PushClip(clip)) }
        portableBounds = null
        portableIneligibleSampledImages = 0
    }

    commands.forEach { command ->
        when (command) {
            is DrawCommand.FillRectangle -> {
                portable.add(command)
                portableBounds = includeFabricVisibleBounds(portableBounds, command.bounds, activeClips, viewportBounds)
            }

            is DrawCommand.BlitImage -> {
                portable.add(command)
                portableBounds = includeFabricVisibleBounds(portableBounds, command.destination, activeClips, viewportBounds)
            }

            is DrawCommand.SampledImage -> {
                if (isDirectFabricSampledImage(command)) {
                    flushPortable()
                    val visibleClip = activeClips.fold(viewportBounds, ::intersectFabricBounds)
                    command.destination.enclosingFabricViewportBounds(visibleClip)?.let { visible ->
                        layers.add(FabricMinecraftFrameLayer.Sampled(command, visibleClip.takeIf { activeClips.isNotEmpty() }, visible))
                    }
                } else {
                    portable.add(command)
                    val visibleClip = activeClips.fold(viewportBounds, ::intersectFabricBounds)
                    command.destination.enclosingFabricViewportBounds(visibleClip)?.let { bounds ->
                        portableBounds = includeFabricVisibleBounds(portableBounds, bounds, activeClips, viewportBounds)
                        portableIneligibleSampledImages = Math.incrementExact(portableIneligibleSampledImages)
                    }
                }
            }

            is DrawCommand.BlitImagePixels -> {
                portable.add(command)
                portableBounds = includeFabricVisibleBounds(portableBounds, command.destination, activeClips, viewportBounds)
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
                val clip = activeClips.fold(viewportBounds, ::intersectFabricBounds)
                layers.add(FabricMinecraftFrameLayer.Platform(command, clip.takeIf { activeClips.isNotEmpty() }))
            }
        }
    }
    require(activeClips.isEmpty()) { "Clip push has no matching pop." }
    flushPortable()
    return layers
}

/**
 * Converts one capacity-starved direct layer into an equivalent tight portable fallback.
 *
 * @param layer sampled layer whose original destination and effective clip are retained.
 * @return localized portable layer bounded to visible output only.
 */
@JvmSynthetic
internal fun portableFabricSampledFallback(layer: FabricMinecraftFrameLayer.Sampled): FabricMinecraftFrameLayer.Portable {
    val bounds = layer.visibleBounds
    val offset = IntOffset(-bounds.left, -bounds.top)
    val commands = ArrayList<DrawCommand>(3)
    layer.clip?.let { clip -> commands.add(DrawCommand.PushClip(intersectFabricBounds(clip, bounds) + offset)) }
    val destination = layer.command.destination
    commands.add(
        layer.command.copy(
            destination = FloatRect(destination.left + offset.x, destination.top + offset.y, destination.right + offset.x, destination.bottom + offset.y),
        ),
    )
    if (layer.clip != null) commands.add(DrawCommand.PopClip)
    return FabricMinecraftFrameLayer.Portable(commands, bounds)
}

/**
 * Forwards one logical rectangle as the absolute corner coordinates required by the modern GUI extractor texture overload.
 *
 * [submit] is borrowed synchronously on the caller's thread, invoked exactly once, and never retained.
 * Any exception from [submit] propagates without translation.
 *
 * @param bounds logical destination whose right and bottom edges are absolute coordinates rather than extents.
 * @param submit borrowed native call receiving `x0`, `y0`, `x1`, and `y1` in that order.
 */
@JvmSynthetic
internal inline fun submitFabricMinecraftGuiCorners(
    bounds: IntRect,
    submit: (x0: Int, y0: Int, x1: Int, y1: Int) -> Unit,
) {
    submit(bounds.left, bounds.top, bounds.right, bounds.bottom)
}

/**
 * Checks the platform-independent direct subset before any native texture-capacity lookup.
 *
 * @param command immutable sampled command whose constructor already validates source containment.
 * @return true when native nearest sampling can preserve its source and compositing contract.
 */
@JvmSynthetic
internal fun isDirectFabricSampledImage(command: DrawCommand.SampledImage): Boolean =
    command.orientation == SampledImageOrientation.Normal && command.tint == ArgbColor(-1) && command.alphaCutoff == 0f &&
        command.source.left.isWholeTexel() && command.source.top.isWholeTexel() && command.source.right.isWholeTexel() && command.source.bottom.isWholeTexel()

private fun Float.isWholeTexel(): Boolean = toDouble() == floor(toDouble())

private fun includeFabricVisibleBounds(
    accumulated: IntRect?,
    commandBounds: IntRect,
    activeClips: List<IntRect>,
    viewportBounds: IntRect,
): IntRect? {
    val visible = activeClips.fold(intersectFabricBounds(viewportBounds, commandBounds), ::intersectFabricBounds)
    if (visible.width <= 0 || visible.height <= 0) return accumulated
    val previous = accumulated ?: return visible
    return IntRect(
        minOf(previous.left, visible.left),
        minOf(previous.top, visible.top),
        maxOf(previous.right, visible.right),
        maxOf(previous.bottom, visible.bottom),
    )
}

private fun localizeFabricPortable(
    commands: List<DrawCommand>,
    bounds: IntRect,
): List<DrawCommand> {
    val offset = IntOffset(-bounds.left, -bounds.top)
    return commands.mapNotNull { command ->
        when (command) {
            is DrawCommand.FillRectangle -> {
                val visible = intersectFabricBounds(command.bounds, bounds)
                if (visible.width <= 0 || visible.height <= 0) null else DrawCommand.FillRectangle(visible + offset, command.color)
            }

            is DrawCommand.BlitImage -> {
                val visible = intersectFabricBounds(command.destination, bounds)
                if (visible.width <= 0 || visible.height <= 0) null else DrawCommand.BlitImage(command.image, command.source, command.destination + offset)
            }

            is DrawCommand.SampledImage -> {
                val destination = command.destination
                command.copy(destination = FloatRect(destination.left + offset.x, destination.top + offset.y, destination.right + offset.x, destination.bottom + offset.y))
            }

            is DrawCommand.BlitImagePixels -> {
                val visible = intersectFabricBounds(command.destination, bounds)
                if (visible.width <= 0 || visible.height <= 0) null else command.copy(destination = command.destination + offset)
            }

            is DrawCommand.PushClip -> {
                DrawCommand.PushClip(intersectFabricBounds(command.bounds, bounds) + offset)
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

private fun intersectFabricBounds(
    first: IntRect,
    second: IntRect,
): IntRect {
    val left = maxOf(first.left, second.left)
    val top = maxOf(first.top, second.top)
    val right = maxOf(left, minOf(first.right, second.right))
    val bottom = maxOf(top, minOf(first.bottom, second.bottom))
    return IntRect(left, top, right, bottom)
}
