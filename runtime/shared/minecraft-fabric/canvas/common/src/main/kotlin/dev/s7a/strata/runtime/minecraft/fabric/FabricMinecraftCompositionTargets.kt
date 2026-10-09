package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Borrows two alternating destination pairs and two owned metadata pairs.
 * A generation-owned optional scratch pair is borrowed; the final output and metadata transfer to the enclosing native storage owner.
 */
internal class FabricMinecraftCompositionTargets<T : AutoCloseable, V : AutoCloseable> private constructor(
    /**
     * Alternating RGBA8 outputs, both generation-owned before any upload or draw.
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
         * Factories select exact formats and flags; an optional same-extent [scratch] belongs to the same generation.
         * [passCount] fixes parity so the final result always belongs to this tile, and failures preserve earlier allocations for fenced cleanup.
         */
        @JvmSynthetic
        @Suppress("LongParameterList") // Borrowed scratch parity joins the existing exact native allocation factories.
        internal fun <T : AutoCloseable, V : AutoCloseable> create(
            owned: FabricMinecraftNativeStorage,
            size: IntSize,
            indexSize: IntSize,
            factorSize: IntSize,
            target: (IntSize) -> T,
            metadata: (String, IntSize) -> T,
            view: (T) -> V,
            scratch: Pair<T, V>? = null,
            passCount: Int = 0,
        ): FabricMinecraftCompositionTargets<T, V> {
            val outputs =
                if (scratch == null) {
                    (0..1).map {
                        val texture = owned.allocate { target(size) }
                        texture to owned.allocate { view(texture) }
                    }
                } else {
                    val texture = owned.allocate { target(size) }
                    val output = texture to owned.allocate { view(texture) }
                    // The last pass must land in the tile's independently owned immutable output.
                    if (passCount % 2 == 0) listOf(output, scratch) else listOf(scratch, output)
                }
            val indices = owned.allocate { metadata("Strata ordered composition axes", indexSize) }
            val indexView = owned.allocate { view(indices) }
            val factors = owned.allocate { metadata("Strata binary32 source factors", factorSize) }
            val factorView = owned.allocate { view(factors) }
            return FabricMinecraftCompositionTargets(outputs, indices to indexView, factors to factorView)
        }
    }
}
