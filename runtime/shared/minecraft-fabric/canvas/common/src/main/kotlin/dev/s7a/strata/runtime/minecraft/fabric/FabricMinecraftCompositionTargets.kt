package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Borrows two destination pairs and two metadata pairs already transferred to the enclosing native storage owner.
 * This initialization-local description never closes resources; every allocation follows the same ownership order on all device adapters.
 */
internal class FabricMinecraftCompositionTargets<T : AutoCloseable, V : AutoCloseable> private constructor(
    /**
     * Alternating RGBA8 outputs, both owned before any upload or draw.
     */
    @get:JvmSynthetic
    internal val destinations: List<Pair<T, V>>,
    /**
     * Exact coordinate metadata texture and its borrowed view.
     */
    @get:JvmSynthetic
    internal val indices: Pair<T, V>,
    /**
     * Float-bit metadata texture and its borrowed view.
     */
    @get:JvmSynthetic
    internal val factors: Pair<T, V>,
) {
    /**
     * Allocates the fixed composition shape without owning any adapter, image or source beyond initialization.
     */
    internal companion object {
        /**
         * Records each destination, metadata texture and view in [owned] before invoking the next borrowed allocator.
         * Factories select the adapter's exact formats and usage flags; failures preserve every earlier resource for fenced cleanup.
         */
        @JvmSynthetic
        internal fun <T : AutoCloseable, V : AutoCloseable> create(
            owned: FabricMinecraftNativeStorage,
            size: IntSize,
            indexSize: IntSize,
            factorSize: IntSize,
            target: (IntSize) -> T,
            metadata: (String, IntSize) -> T,
            view: (T) -> V,
        ): FabricMinecraftCompositionTargets<T, V> {
            val outputs =
                (0..1).map {
                    val texture = owned.allocate { target(size) }
                    texture to owned.allocate { view(texture) }
                }
            val indices = owned.allocate { metadata("Strata ordered composition axes", indexSize) }
            val indexView = owned.allocate { view(indices) }
            val factors = owned.allocate { metadata("Strata binary32 source factors", factorSize) }
            val factorView = owned.allocate { view(factors) }
            return FabricMinecraftCompositionTargets(outputs, indices to indexView, factors to factorView)
        }
    }
}
