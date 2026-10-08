package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Detached source requests in first-occurrence referential-identity order, without native storage.
 *
 * Construction consumes [sources] once and retains at most one list entry and identity-set entry per distinct source.
 * Prepared frame inputs own this immutable presentation state on the render thread; replacement, detachment and close release it with those inputs.
 * Native availability, owner capacity and pinning remain device operations on every borrow.
 */
internal class FabricMinecraftSampledImageRequests(
    sources: Sequence<DrawImage>,
) {
    private val identities = Collections.newSetFromMap(IdentityHashMap<DrawImage, Boolean>())

    /**
     * Distinct source identities in original request order, detached from the supplied sequence.
     */
    @get:JvmSynthetic
    internal val images: List<DrawImage> = sources.filter(identities::add).toList()

    /**
     * Tests referential membership without rebuilding the prepared identity set.
     */
    @JvmSynthetic
    internal operator fun contains(image: DrawImage): Boolean = image in identities
}
