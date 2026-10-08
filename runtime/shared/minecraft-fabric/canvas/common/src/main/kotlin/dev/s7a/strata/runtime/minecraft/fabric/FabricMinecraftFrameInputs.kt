package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.render.DrawImage

/**
 * Derived CPU inputs owned by one prepared display list and GUI scale, without native texture references.
 *
 * Complete maps may be borrowed from the immediately previous inputs under exact original keys and a new admission ledger.
 * Preparation-local factor sharing and input matching retain no previous-input chain; screen release drops the current inputs.
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
        preparedPortable ?: preparePortable(layers, scale, compositionEnabled, emptyList())

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
     * Distinct source requests prepared once across direct layers and complete ordered composition.
     */
    @get:JvmSynthetic
    internal val sampledRequests: FabricMinecraftSampledImageRequests =
        FabricMinecraftSampledImageRequests(
            sequence {
                var index = 0
                layers.forEach { layer ->
                    when (layer) {
                        is FabricMinecraftFrameLayer.Portable -> {
                            portable[index++].composition?.sources?.forEach { if (it != null) yield(it) }
                        }

                        is FabricMinecraftFrameLayer.Sampled -> {
                            yield(layer.command.image)
                            if (layer.sampling != null) index += 1
                        }

                        is FabricMinecraftFrameLayer.Platform -> {
                        }
                    }
                }
            },
        )

    /**
     * Distinct requested image identities in first-occurrence display-list order, retaining no native storage.
     */
    @get:JvmSynthetic
    internal val sampled: List<DrawImage>
        get() = sampledRequests.images

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
        val direct = replacements?.let { prepare(it, scale, compositionEnabled, this, capacity, ineligible) } ?: this
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

    /**
     * Builds only the next current CPU descriptions; native source availability remains a separate recurring borrow.
     */
    internal companion object {
        /**
         * Borrows [previous] synchronously for bounded exact matching, then returns independent current inputs.
         * Reused complete maps spend the new budget in display-list order; no previous-frame owner is stored.
         * The original six-argument constructor remains available, and publication occurs only after the caller presents successfully.
         */
        @JvmSynthetic
        internal fun prepare(
            layers: List<FabricMinecraftFrameLayer>,
            scale: Int,
            compositionEnabled: Boolean,
            previous: FabricMinecraftFrameInputs? = null,
            capacity: Long = 0L,
            ineligible: Long = 0L,
        ): FabricMinecraftFrameInputs =
            FabricMinecraftFrameInputs(layers, scale, capacity, ineligible, compositionEnabled, preparePortable(layers, scale, compositionEnabled, previous?.portable.orEmpty()))

        /**
         * Applies this traversal's independent admission and bounded complete/axis proofs to current portable descriptions.
         * The original constructor also calls this entry; JVM-synthetic visibility avoids a private companion accessor bridge.
         * [previous] is borrowed only during the call, and no workspace or previous-input owner survives the result.
         */
        @JvmSynthetic
        internal fun preparePortable(
            layers: List<FabricMinecraftFrameLayer>,
            scale: Int,
            enabled: Boolean,
            previous: List<FabricMinecraftPortableImage>,
        ): List<FabricMinecraftPortableImage> {
            val budget = FabricMinecraftSamplingBudget()
            layers.forEach { if (it is FabricMinecraftFrameLayer.Sampled && it.sampling != null) check(budget.admit(it.command.destination, it.visibleBounds, scale)) }
            val inputs =
                layers.mapNotNull {
                    when (it) {
                        is FabricMinecraftFrameLayer.Portable -> {
                            val origin = if (it.absoluteCoordinates) IntOffset(it.bounds.left, it.bounds.top) else IntOffset.Zero
                            FabricMinecraftPortableImage(it.commands, it.bounds.size, scale, origin)
                        }

                        is FabricMinecraftFrameLayer.Sampled -> {
                            it.sampling?.let { sampling -> FabricMinecraftPortableImage(listOf(it.command), it.visibleBounds.size, scale, IntOffset(it.visibleBounds.left, it.visibleBounds.top), sampling) }
                        }

                        is FabricMinecraftFrameLayer.Platform -> null
                    }
                }
            if (enabled.not()) return inputs
            val matches = matchFabricMinecraftPreparedInputs(previous, inputs)
            val axes = matchFabricMinecraftPreparedAxes(previous, inputs, matches)
            val factors = FabricMinecraftCompositionFactors()
            return inputs.mapIndexed { index, input ->
                val old = previous.getOrNull(matches[index])?.composition
                val composition =
                    when {
                        input.sampling != null -> null
                        old != null -> {
                            if (budget.admitComposition(old.physicalSize, old.uploadBytes / 4L, old.sources.size)) {
                                factors.get(old.orderedTints, old.factors)
                                old
                            } else {
                                null
                            }
                        }

                        FabricMinecraftCompositionMap.shouldCompose(input.commands) -> {
                            FabricMinecraftCompositionMap.create(input, budget, factors, previous.getOrNull(axes[index])?.composition)
                        }
                        else -> null
                    }
                if (composition == null) input else FabricMinecraftPortableImage(input.commands, input.size, scale, input.origin, composition = composition)
            }
        }
    }
}
