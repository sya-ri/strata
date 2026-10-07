@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One ordered native presentation layer produced from a committed portable display list.
 *
 * Portable commands retain tight visible bounds and exact sampling coordinates, eligible sampled images retain their immutable source identity and floating destination, and platform payloads remain opaque.
 */
internal sealed interface FabricMinecraftFrameLayer {
    /**
     * A tight CPU-rasterized fallback run; sampled runs preserve absolute coordinates for exact Float arithmetic.
     * Disjoint tiles from one original run share an opaque ordering identity, owned only by the current frame descriptions.
     */
    class Portable(
        @get:JvmSynthetic
        internal val commands: List<DrawCommand>,
        @get:JvmSynthetic
        internal val bounds: IntRect,
        @get:JvmSynthetic
        internal val ineligibleSampledImages: Int = 0,
        @get:JvmSynthetic
        internal val absoluteCoordinates: Boolean = false,
        @get:JvmSynthetic
        internal val capacitySampledImages: Int = 0,
        @get:JvmSynthetic
        internal val tintFallbackImages: Int = commands.count { it is DrawCommand.SampledImage && it.tint != ArgbColor(-1) },
        @get:JvmSynthetic
        internal val alphaCutoffFallbackImages: Int = commands.count { it is DrawCommand.SampledImage && it.tint == ArgbColor(-1) && it.alphaCutoff != 0f },
        @get:JvmSynthetic
        internal val orderingGroup: Any? = null,
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
        @get:JvmSynthetic
        internal val sampling: FabricMinecraftSamplingMap? = null,
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
 * Submits resolved frame layers in display-list order with native boundaries between original runs and image or platform barriers.
 * Adjacent disjoint tiles sharing one original portable run's identity need no additional ordering boundary.
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
    var previousGroup: Any? = null
    layers.forEachIndexed { index, layer ->
        val group = (layer as? FabricMinecraftFrameLayer.Portable)?.orderingGroup
        if (0 < index && (group == null || group !== previousGroup)) advance()
        submit(layer)
        previousGroup = group
    }
}

/**
 * Partitions one committed frame into tight portable runs, independently cacheable sampled images, and platform barriers.
 *
 * Direct eligibility requires opaque-white tint and zero alpha cutoff; exact lookup adapters also preserve mirrored orientations.
 * Adapters with floating UV submission may admit fractional source edges; other adapters require integer texel edges.
 * Unsupported sampled commands remain inside the existing portable path without changing their pixels or ordering.
 * Invisible portable primitives are omitted from pixel inputs; direct-image barriers retain their exact display-list positions.
 *
 * @param commands complete balanced display list.
 * @param viewport positive or empty logical viewport used only for visibility and bounded fallback allocation.
 * @param scale positive final physical pixel density used to resolve fractional clip coverage without changing source sampling.
 * @return immutable layers in exact display-list order.
 */
@JvmSynthetic
// Keep clip-stack changes and layer flushes in one ordered traversal of the exhaustive command variants.
@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun partitionFabricMinecraftFrame(
    commands: List<DrawCommand>,
    viewport: IntSize,
    scale: Int = 1,
    fractionalSource: Boolean = false,
    exactSampling: Boolean = false,
): List<FabricMinecraftFrameLayer> {
    require(0 < scale) { "Minecraft GUI scale must be positive." }
    val layers = ArrayList<FabricMinecraftFrameLayer>()
    val activeClips = ArrayList<IntRect>()
    val activeClipCommands = ArrayList<DrawCommand>()
    val viewportBounds = IntRect(0, 0, viewport.width, viewport.height)
    var portable = ArrayList<DrawCommand>()
    var portableBounds: IntRect? = null
    var portableIneligibleSampledImages = 0
    var portableCapacitySampledImages = 0
    val samplingBudget = FabricMinecraftSamplingBudget()

    fun flushPortable() {
        val bounds = portableBounds
        if (bounds != null) {
            repeat(activeClips.size) { portable.add(DrawCommand.PopClip) }
            layers.addAll(tileFabricMinecraftPortable(portable, bounds, scale, portableIneligibleSampledImages, portableCapacitySampledImages))
        }
        portable = ArrayList()
        portable.addAll(activeClipCommands)
        portableBounds = null
        portableIneligibleSampledImages = 0
        portableCapacitySampledImages = 0
    }

    commands.forEach { command ->
        when (command) {
            is DrawCommand.FillRectangle -> {
                val visible = visibleFabricBounds(command.bounds, activeClips, viewportBounds) ?: return@forEach
                portable.add(command)
                portableBounds = includeFabricVisibleBounds(portableBounds, visible)
            }

            is DrawCommand.BlitImage -> {
                val visible = visibleFabricBounds(command.destination, activeClips, viewportBounds) ?: return@forEach
                portable.add(command)
                portableBounds = includeFabricVisibleBounds(portableBounds, visible)
            }

            is DrawCommand.SampledImage -> {
                val visibleClip = activeClips.fold(viewportBounds, ::intersectFabricBounds)
                val ordinary = isDirectFabricSampledImage(command, scale, fractionalSource)
                var directClip =
                    if (isDirectFabricSampledImage(command, scale, fractionalSource, exactSampling)) {
                        if (fractionalClipsContain(activeClipCommands, command.destination, visibleClip)) {
                            visibleClip
                        } else {
                            pixelAlignedFabricSampledClip(activeClipCommands, visibleClip, scale)
                        }
                    } else {
                        null
                    }
                val capacity = directClip != null && ordinary.not() && samplingBudget.admit(command.destination, directClip, scale).not()
                if (capacity) directClip = null
                if (directClip != null) {
                    flushPortable()
                    command.destination.enclosingFabricViewportBounds(directClip)?.let { visible ->
                        layers.add(sampledFabricLayer(command, directClip.takeIf { activeClips.isNotEmpty() }, visible, scale, fractionalSource))
                    }
                } else {
                    val visible = command.destination.enclosingFabricViewportBounds(visibleClip) ?: return@forEach
                    portable.add(command)
                    portableBounds = includeFabricVisibleBounds(portableBounds, visible)
                    if (capacity) {
                        portableCapacitySampledImages = Math.incrementExact(portableCapacitySampledImages)
                    } else {
                        portableIneligibleSampledImages = Math.incrementExact(portableIneligibleSampledImages)
                    }
                }
            }

            is DrawCommand.BlitImagePixels -> {
                val visible = visibleFabricBounds(command.destination, activeClips, viewportBounds) ?: return@forEach
                portable.add(command)
                portableBounds = includeFabricVisibleBounds(portableBounds, visible)
            }

            is DrawCommand.PushClip -> {
                activeClips.add(command.bounds)
                activeClipCommands.add(command)
                portable.add(command)
            }

            is DrawCommand.PushFractionalClip -> {
                val bounds = command.bounds
                activeClips.add(
                    IntRect(
                        floor(bounds.left.coerceIn(0f, viewport.width.toFloat())).toInt(),
                        floor(bounds.top.coerceIn(0f, viewport.height.toFloat())).toInt(),
                        ceil(bounds.right.coerceIn(0f, viewport.width.toFloat())).toInt(),
                        ceil(bounds.bottom.coerceIn(0f, viewport.height.toFloat())).toInt(),
                    ),
                )
                activeClipCommands.add(command)
                portable.add(command)
            }

            DrawCommand.PopClip -> {
                require(activeClips.isNotEmpty()) { "Clip pop has no matching push." }
                activeClips.removeAt(activeClips.lastIndex)
                activeClipCommands.removeAt(activeClipCommands.lastIndex)
                portable.add(command)
            }

            is DrawCommand.Platform -> {
                val bounds = command.bounds
                val clip = activeClips.fold(viewportBounds, ::intersectFabricBounds)
                require(fractionalClipsContain(activeClipCommands, FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()), clip)) {
                    "Opaque platform drawing intersecting a fractional clip is unsupported."
                }
                flushPortable()
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
 * @return original-coordinate portable layer bounded to visible output only.
 */
@JvmSynthetic
internal fun portableFabricSampledFallback(layer: FabricMinecraftFrameLayer.Sampled): FabricMinecraftFrameLayer.Portable {
    val bounds = layer.visibleBounds
    val commands = ArrayList<DrawCommand>(3)
    layer.clip?.let { clip -> commands.add(DrawCommand.PushClip(intersectFabricBounds(clip, bounds))) }
    commands.add(layer.command)
    if (layer.clip != null) commands.add(DrawCommand.PopClip)
    return FabricMinecraftFrameLayer.Portable(commands, bounds, absoluteCoordinates = true)
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

private fun sampledFabricLayer(
    command: DrawCommand.SampledImage,
    clip: IntRect?,
    visible: IntRect,
    scale: Int,
    fractionalSource: Boolean,
): FabricMinecraftFrameLayer.Sampled {
    val sampling = if (isDirectFabricSampledImage(command, scale, fractionalSource)) null else FabricMinecraftSamplingMap(command, visible, scale)
    return FabricMinecraftFrameLayer.Sampled(command, clip, visible, sampling)
}

// Fractional clip edges discard physical pixel centers, rather than enclosing every touched logical cell.
// Admit only coverage representable by the existing integer GUI scissor; source UVs and destination stay untouched.
private fun pixelAlignedFabricSampledClip(
    clips: List<DrawCommand>,
    integerClip: IntRect,
    scale: Int,
): IntRect? {
    var left = integerClip.left.toDouble()
    var top = integerClip.top.toDouble()
    var right = integerClip.right.toDouble()
    var bottom = integerClip.bottom.toDouble()
    clips.forEach { command ->
        if (command is DrawCommand.PushFractionalClip) {
            left = maxOf(left, command.bounds.left.toDouble()).coerceAtMost(integerClip.right.toDouble())
            top = maxOf(top, command.bounds.top.toDouble()).coerceAtMost(integerClip.bottom.toDouble())
            right = minOf(right, command.bounds.right.toDouble()).coerceAtLeast(integerClip.left.toDouble())
            bottom = minOf(bottom, command.bounds.bottom.toDouble()).coerceAtLeast(integerClip.top.toDouble())
        }
    }

    fun edge(value: Double): Double = ceil(value * scale - 0.5).coerceAtLeast(0.0) / scale
    val resolvedLeft = edge(left)
    val resolvedTop = edge(top)
    val resolvedRight = edge(maxOf(left, right))
    val resolvedBottom = edge(maxOf(top, bottom))
    if (listOf(resolvedLeft, resolvedTop, resolvedRight, resolvedBottom).any { it != floor(it) }) return null
    return IntRect(resolvedLeft.toInt(), resolvedTop.toInt(), resolvedRight.toInt(), resolvedBottom.toInt())
}

private fun visibleFabricBounds(
    commandBounds: IntRect,
    activeClips: List<IntRect>,
    viewportBounds: IntRect,
): IntRect? {
    val visible = activeClips.fold(intersectFabricBounds(viewportBounds, commandBounds), ::intersectFabricBounds)
    return visible.takeIf { 0 < it.width && 0 < it.height }
}

private fun includeFabricVisibleBounds(
    accumulated: IntRect?,
    visible: IntRect,
): IntRect {
    val previous = accumulated ?: return visible
    return IntRect(
        minOf(previous.left, visible.left),
        minOf(previous.top, visible.top),
        maxOf(previous.right, visible.right),
        maxOf(previous.bottom, visible.bottom),
    )
}

/**
 * Localizes integer-only portable runs while preserving their ordered clips and image mapping.
 */
@JvmSynthetic
// Every portable command variant has an explicit coordinate conversion; splitting the visitor obscures clip balance.
@Suppress("CyclomaticComplexMethod")
internal fun localizeFabricPortable(
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

            is DrawCommand.PushFractionalClip -> {
                val clip = command.bounds
                val left = clip.left.coerceIn(bounds.left.toFloat(), bounds.right.toFloat())
                val top = clip.top.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat())
                val right = clip.right.coerceIn(left, bounds.right.toFloat())
                val bottom = clip.bottom.coerceIn(top, bounds.bottom.toFloat())
                DrawCommand.PushFractionalClip(FloatRect(left + offset.x, top + offset.y, right + offset.x, bottom + offset.y))
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

private fun fractionalClipsContain(
    clips: List<DrawCommand>,
    bounds: FloatRect,
    integerClip: IntRect,
): Boolean {
    val left = maxOf(bounds.left, integerClip.left.toFloat())
    val top = maxOf(bounds.top, integerClip.top.toFloat())
    val right = minOf(bounds.right, integerClip.right.toFloat())
    val bottom = minOf(bounds.bottom, integerClip.bottom.toFloat())
    return right <= left || bottom <= top ||
        clips.all { command ->
            if (command !is DrawCommand.PushFractionalClip) return@all true
            val horizontal = command.bounds.left <= left && right <= command.bounds.right
            val vertical = command.bounds.top <= top && bottom <= command.bounds.bottom
            horizontal && vertical
        }
}
