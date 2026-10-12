package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayer.SourceRejection
import dev.s7a.strata.runtime.render.DrawCommand
import java.util.IdentityHashMap

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
     * Current immutable-command preparation callback shared only with this class's companion.
     * Synthetic internal access avoids a generated cross-class access bridge; no native callback is retained.
     */
    @get:JvmSynthetic
    @set:JvmSynthetic
    internal var repartition: ((List<SourceRejection?>) -> FabricMinecraftFrameInputs)? = null
    private var fallbackReasons: List<SourceRejection?>? = null
    private var sourceFallback: FabricMinecraftFrameInputs? = null

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
     * Resolves availability through the original two-callback CPU contract.
     * Native source callers also supply uploaded alpha through the three-callback overload.
     */
    @JvmSynthetic
    internal fun resolve(
        available: (DrawImage) -> Boolean,
        supported: (DrawImage) -> Boolean,
    ): FabricMinecraftFrameInputs = resolve(available, supported, { false })

    /**
     * Returns these inputs unchanged when every direct layer is available and has exact native alpha, or resolves portable fallbacks.
     * The callbacks are synchronous and never retained; [supported] classifies each absent direct layer in display-list order.
     * Fallback counts describe this borrow only, preserving per-command capacity and ineligible accounting without native handles.
     * [partialAlpha] reads only classification retained by the current borrowed native source; unavailable sources are never inspected.
     */
    @JvmSynthetic
    internal fun resolve(
        available: (DrawImage) -> Boolean,
        supported: (DrawImage) -> Boolean,
        partialAlpha: (DrawImage) -> Boolean,
    ): FabricMinecraftFrameInputs {
        if (repartition != null) return resolveSources(available, supported, partialAlpha)
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

    private fun resolveSources(
        available: (DrawImage) -> Boolean,
        supported: (DrawImage) -> Boolean,
        partialAlpha: (DrawImage) -> Boolean,
    ): FabricMinecraftFrameInputs {
        var reasons: MutableList<SourceRejection?>? = null
        var count = 0
        layers.forEach { layer ->
            if (layer is FabricMinecraftFrameLayer.Sampled) {
                val reason = sourceRejection(layer, available, supported, partialAlpha)
                if (reason != null && reasons == null) reasons = ArrayList<SourceRejection?>().also { repeat(count) { _ -> it.add(null) } }
                reasons?.add(reason)
                count += 1
            }
        }
        sampled.forEach { image ->
            val reason = if (available(image)) null else SourceRejection.Capacity
            if (reason != null && reasons == null) reasons = ArrayList<SourceRejection?>().also { repeat(count) { _ -> it.add(null) } }
            reasons?.add(reason)
            count += 1
        }
        val rejected = reasons ?: return this
        if (rejected != fallbackReasons) {
            sourceFallback = checkNotNull(repartition).invoke(rejected).resolveCompositions(available)
            fallbackReasons = rejected
        }
        return checkNotNull(sourceFallback)
    }

    // Classify only the current borrowed source; never inspect alpha metadata for absent storage.
    private fun sourceRejection(
        layer: FabricMinecraftFrameLayer.Sampled,
        available: (DrawImage) -> Boolean,
        supported: (DrawImage) -> Boolean,
        partialAlpha: (DrawImage) -> Boolean,
    ): SourceRejection? {
        val image = layer.command.image
        return when {
            available(image).not() -> if (supported(image)) SourceRejection.Capacity else SourceRejection.Ineligible
            layer.command.alphaCutoff == 0f && partialAlpha(image) -> SourceRejection.Composition
            else -> null
        }
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
     * Prepares complete display-list inputs whose native fallback can retain original composition before any background flush.
     */
    internal companion object {
        /**
         * Retains only the current immutable commands and at most one derived fallback in this display-list owner.
         * Source availability and uploaded alpha are rechecked each borrow; a changed rejection signature replaces that fallback.
         * Display-list, viewport or scale replacement and screen release drop the enclosing inputs and every derived description.
         * No native handle, source pixel copy, pin, external callback or device ownership enters this prepared state.
         */
        @JvmSynthetic
        internal fun prepare(
            commands: List<DrawCommand>,
            viewport: IntSize,
            scale: Int,
            fractionalSource: Boolean = false,
            exactSampling: Boolean = false,
            compositionEnabled: Boolean = false,
        ): FabricMinecraftFrameInputs {
            val layers = partitionFabricMinecraftFrame(commands, viewport, scale, fractionalSource, exactSampling)
            return FabricMinecraftFrameInputs(layers, scale, compositionEnabled = compositionEnabled).also { inputs ->
                inputs.repartition = { rejected ->
                    val reasons = IdentityHashMap<DrawCommand.SampledImage, SourceRejection>()
                    var index = 0
                    layers.forEach { layer ->
                        if (layer is FabricMinecraftFrameLayer.Sampled) rejected[index++]?.let { reasons[layer.command] = it }
                    }
                    // A native image barrier before a translucent or unknown source would separately round the original background.
                    // Keep the complete original portable runs; opaque platform barriers and their active clips remain untouched.
                    val resolved = partitionFabricMinecraftFrameWithSourceAdmission(commands, viewport, scale, fractionalSource, exactSampling) { reasons[it] ?: SourceRejection.Composition }
                    val compose = compositionEnabled && rejected.none { it == SourceRejection.Capacity || it == SourceRejection.Ineligible }
                    FabricMinecraftFrameInputs(resolved, scale, compositionEnabled = compose)
                }
            }
        }
    }
}
