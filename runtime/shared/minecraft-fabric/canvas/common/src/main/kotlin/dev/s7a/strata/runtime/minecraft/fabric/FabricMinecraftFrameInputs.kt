package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage

/**
 * Derived CPU inputs owned by one prepared display list and GUI scale, without native texture references.
 *
 * Reuse lasts only while the display-list identity, viewport and scale remain unchanged; screen release drops these inputs.
 * Texture availability is checked inside every native borrow, so reload and capacity fallback never reuse stale device state.
 */
internal class FabricMinecraftFrameInputs(
    @get:JvmSynthetic internal val layers: List<FabricMinecraftFrameLayer>,
    private val scale: Int,
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
        layers.filterIsInstance<FabricMinecraftFrameLayer.Portable>().map { FabricMinecraftPortableImage(it.commands, it.bounds.size, scale) }

    /**
     * Number of unsupported sampled commands contained in the prepared portable runs.
     */
    @get:JvmSynthetic
    internal val ineligibleSampledImages: Long =
        layers.filterIsInstance<FabricMinecraftFrameLayer.Portable>().fold(0L) { count, layer -> Math.addExact(count, layer.ineligibleSampledImages.toLong()) }

    /**
     * Returns these inputs unchanged when every direct layer is available, or constructs this borrow's portable fallbacks.
     * The callbacks are synchronous and never retained; [unavailable] runs once for each absent direct layer in display-list order.
     */
    @JvmSynthetic
    internal fun resolve(
        available: (DrawImage) -> Boolean,
        unavailable: (DrawImage) -> Unit,
    ): FabricMinecraftFrameInputs {
        var replacements: MutableList<FabricMinecraftFrameLayer>? = null
        layers.forEachIndexed { index, layer ->
            if (layer is FabricMinecraftFrameLayer.Sampled && available(layer.command.image).not()) {
                unavailable(layer.command.image)
                val resolved = replacements ?: layers.toMutableList().also { replacements = it }
                resolved[index] = portableFabricSampledFallback(layer)
            }
        }
        return replacements?.let { FabricMinecraftFrameInputs(it, scale) } ?: this
    }
}
