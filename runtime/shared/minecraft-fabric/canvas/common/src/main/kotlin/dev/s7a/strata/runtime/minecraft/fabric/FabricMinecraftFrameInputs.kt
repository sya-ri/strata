package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.render.DrawCommand

/**
 * Derived CPU inputs owned by one prepared display list and GUI scale, without native texture references.
 *
 * Reuse lasts only while the display-list identity, viewport and scale remain unchanged; screen release drops these inputs.
 * Texture availability is checked inside every native borrow, so reload and capacity fallback never reuse stale device state.
 * Resolved fallback counts belong to that borrow and distinguish unavailable supported images from unsupported images.
 */
internal class FabricMinecraftFrameInputs(
    @get:JvmSynthetic internal val layers: List<FabricMinecraftFrameLayer>,
    private val scale: Int,
    capacitySampledImages: Long = 0L,
    unavailableIneligibleImages: Long = 0L,
) {
    /**
     * Counts exact-output budget exhaustion and unavailable supported source identities in this native borrow.
     */
    @get:JvmSynthetic
    internal val capacitySampledImages: Long =
        layers.fold(capacitySampledImages) { count, layer -> Math.addExact(count, if (layer is FabricMinecraftFrameLayer.Portable) layer.capacitySampledImages.toLong() else 0L) }

    /**
     * Source identities requested in display-list order, retaining no native storage.
     */
    @get:JvmSynthetic
    internal val sampled: List<DrawImage> = buildList { layers.forEach { if (it is FabricMinecraftFrameLayer.Sampled) add(it.command.image) } }

    /**
     * Immutable localized raster descriptions prepared once for the current display list.
     */
    @get:JvmSynthetic
    internal val portable: List<FabricMinecraftPortableImage> =
        layers.mapNotNull {
            when (it) {
                is FabricMinecraftFrameLayer.Portable -> {
                    val origin = if (it.absoluteCoordinates) IntOffset(it.bounds.left, it.bounds.top) else IntOffset.Zero
                    FabricMinecraftPortableImage(it.commands, it.bounds.size, scale, origin)
                }

                is FabricMinecraftFrameLayer.Sampled -> {
                    it.sampling?.let { sampling ->
                        FabricMinecraftPortableImage(listOf(it.command), it.visibleBounds.size, scale, IntOffset(it.visibleBounds.left, it.visibleBounds.top), sampling)
                    }
                }

                is FabricMinecraftFrameLayer.Platform -> {
                    null
                }
            }
        }

    /**
     * Number of unsupported sampled commands in portable runs, including unavailable direct layers in this borrow.
     */
    @get:JvmSynthetic
    internal val ineligibleSampledImages: Long =
        layers.fold(unavailableIneligibleImages) { count, layer -> Math.addExact(count, if (layer is FabricMinecraftFrameLayer.Portable) layer.ineligibleSampledImages.toLong() else 0L) }

    /**
     * Number of visible portable sampled commands rejected first by non-identity tint.
     */
    @get:JvmSynthetic
    internal val tintFallbackImages: Long = countSampled { it.tint != ArgbColor(-1) }

    /**
     * Number rejected by cutoff after identity tint; other causes remain explicitly unclassified.
     */
    @get:JvmSynthetic
    internal val alphaCutoffFallbackImages: Long = countSampled { it.tint == ArgbColor(-1) && it.alphaCutoff != 0f }

    private inline fun countSampled(predicate: (DrawCommand.SampledImage) -> Boolean): Long =
        layers.sumOf { layer ->
            if (layer is FabricMinecraftFrameLayer.Portable) layer.commands.count { it is DrawCommand.SampledImage && predicate(it) }.toLong() else 0L
        }

    /**
     * Returns these inputs unchanged when every direct layer is available, or constructs this borrow's portable fallbacks.
     * The callbacks are synchronous and never retained; [supported] classifies each absent direct layer in display-list order.
     * Fallback counts describe this borrow only, preserving per-command capacity and ineligible accounting without native handles.
     */
    @JvmSynthetic
    internal fun resolve(
        available: (DrawImage) -> Boolean,
        supported: (DrawImage) -> Boolean,
    ): FabricMinecraftFrameInputs {
        var replacements: MutableList<FabricMinecraftFrameLayer>? = null
        var capacity = 0L
        var ineligible = 0L
        layers.forEachIndexed { index, layer ->
            if (layer is FabricMinecraftFrameLayer.Sampled && available(layer.command.image).not()) {
                if (supported(layer.command.image)) capacity += 1L else ineligible += 1L
                val resolved = replacements ?: layers.toMutableList().also { replacements = it }
                resolved[index] = portableFabricSampledFallback(layer)
            }
        }
        return replacements?.let { FabricMinecraftFrameInputs(it, scale, capacity, ineligible) } ?: this
    }
}
