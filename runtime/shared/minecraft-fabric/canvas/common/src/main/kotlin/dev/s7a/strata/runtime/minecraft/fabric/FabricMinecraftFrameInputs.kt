package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.render.DrawImage

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
    @get:JvmSynthetic internal val capacitySampledImages: Long = 0L,
    unavailableIneligibleImages: Long = 0L,
) {
    /**
     * Source identities requested in display-list order, retaining no native storage.
     */
    @get:JvmSynthetic
    internal val sampled: List<DrawImage> = layers.filterIsInstance<FabricMinecraftFrameLayer.Sampled>().map { it.command.image }

    /**
     * Immutable localized raster descriptions prepared once for the current display list.
     */
    @get:JvmSynthetic
    internal val portable: List<FabricMinecraftPortableImage> =
        layers.filterIsInstance<FabricMinecraftFrameLayer.Portable>().map {
            val origin = if (it.absoluteCoordinates) IntOffset(it.bounds.left, it.bounds.top) else IntOffset.Zero
            FabricMinecraftPortableImage(it.commands, it.bounds.size, scale, origin)
        }

    /**
     * Number of unsupported sampled commands in portable runs, including unavailable direct layers in this borrow.
     */
    @get:JvmSynthetic
    internal val ineligibleSampledImages: Long =
        layers.filterIsInstance<FabricMinecraftFrameLayer.Portable>().fold(unavailableIneligibleImages) { count, layer -> Math.addExact(count, layer.ineligibleSampledImages.toLong()) }

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
