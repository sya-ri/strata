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
    capacitySampledImages: Long = 0L,
    unavailableIneligibleImages: Long = 0L,
    private val compositionEnabled: Boolean = false,
    preparedPortable: List<FabricMinecraftPortableImage>? = null,
) {
    private val borrowedCapacity = capacitySampledImages
    private val borrowedIneligible = unavailableIneligibleImages

    /**
     * Immutable localized raster descriptions prepared once for the current display list.
     */
    @get:JvmSynthetic
    internal val portable: List<FabricMinecraftPortableImage> =
        preparedPortable ?: preparePortable()

    private fun preparePortable(): List<FabricMinecraftPortableImage> {
        val budget = FabricMinecraftSamplingBudget()
        layers.forEach { if (it is FabricMinecraftFrameLayer.Sampled && it.sampling != null) check(budget.admit(it.command.destination, it.visibleBounds, scale)) }
        return layers.mapNotNull {
            when (it) {
                is FabricMinecraftFrameLayer.Portable -> {
                    val origin = if (it.absoluteCoordinates) IntOffset(it.bounds.left, it.bounds.top) else IntOffset.Zero
                    val composition = if (compositionEnabled && FabricMinecraftCompositionMap.shouldCompose(it.commands)) FabricMinecraftCompositionMap.create(it.commands, it.bounds.size, scale, origin, budget) else null
                    FabricMinecraftPortableImage(it.commands, it.bounds.size, scale, origin, composition = composition)
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
    }

    private val cpuLayers: List<FabricMinecraftFrameLayer.Portable> =
        buildList {
            var index = 0
            layers.forEach { layer ->
                when (layer) {
                    is FabricMinecraftFrameLayer.Portable -> if (portable[index++].composition == null) add(layer)
                    is FabricMinecraftFrameLayer.Sampled -> if (layer.sampling != null) index += 1
                    is FabricMinecraftFrameLayer.Platform -> Unit
                }
            }
        }

    /**
     * Counts exact-output budget exhaustion and unavailable supported source identities in this native borrow.
     */
    @get:JvmSynthetic
    internal val capacitySampledImages: Long = cpuLayers.fold(capacitySampledImages) { count, layer -> Math.addExact(count, layer.capacitySampledImages.toLong()) }

    /**
     * Source identities requested for direct drawing and complete ordered composition, retaining no native storage.
     */
    @get:JvmSynthetic
    internal val sampled: List<DrawImage> =
        buildList {
            var index = 0
            layers.forEach { layer ->
                when (layer) {
                    is FabricMinecraftFrameLayer.Portable -> {
                        portable[index++].composition?.sources?.forEach { if (it != null) add(it) }
                    }

                    is FabricMinecraftFrameLayer.Sampled -> {
                        add(layer.command.image)
                        if (layer.sampling != null) index += 1
                    }

                    is FabricMinecraftFrameLayer.Platform -> {
                    }
                }
            }
        }

    /**
     * Number of unsupported sampled commands in portable runs, including unavailable direct layers in this borrow.
     */
    @get:JvmSynthetic
    internal val ineligibleSampledImages: Long =
        cpuLayers.fold(unavailableIneligibleImages) { count, layer -> Math.addExact(count, layer.ineligibleSampledImages.toLong()) }

    /**
     * Number of visible portable sampled commands rejected first by unsupported tint composition.
     */
    @get:JvmSynthetic
    internal val tintFallbackImages: Long = cpuLayers.sumOf { it.tintFallbackImages.toLong() }

    /**
     * Number rejected by unsupported cutoff after identity tint; other causes remain explicitly unclassified.
     */
    @get:JvmSynthetic
    internal val alphaCutoffFallbackImages: Long = cpuLayers.sumOf { it.alphaCutoffFallbackImages.toLong() }

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
                val resolved =
                    replacements ?: ArrayList<FabricMinecraftFrameLayer>().also {
                        it.addAll(layers.subList(0, index))
                        replacements = it
                    }
                val fallback = portableFabricSampledFallback(layer)
                resolved.addAll(tileFabricMinecraftPortable(fallback.commands, fallback.bounds, scale))
            } else {
                replacements?.add(layer)
            }
        }
        val direct = replacements?.let { FabricMinecraftFrameInputs(it, scale, capacity, ineligible, compositionEnabled) } ?: this
        return direct.resolveCompositions(available)
    }

    private fun resolveCompositions(available: (DrawImage) -> Boolean): FabricMinecraftFrameInputs {
        if (portable.none { input -> input.composition?.sources?.any { it != null && available(it).not() } == true }) return this
        val resolved =
            portable.map { input ->
                val composition = input.composition
                if (composition != null && composition.sources.any { it != null && available(it).not() }) {
                    FabricMinecraftPortableImage(input.commands, input.size, scale, input.origin, input.sampling)
                } else {
                    input
                }
            }
        return FabricMinecraftFrameInputs(layers, scale, borrowedCapacity, borrowedIneligible, compositionEnabled, resolved)
    }
}
