package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.ui.UiDefinition

/**
 * One owner-confined prepared native workload, borrowing no clock, native handle or mutable cross-frame raster.
 * Immutable fixture inputs may span the bounded declared sample sequence; runtime caches retain only their ordinary bounded state.
 */
internal interface MinecraftNativePerformanceScene {
    /**
     * Enqueues an external revision before the next ordinary frame cutoff, without declarative mutation.
     */
    fun update()

    /**
     * Returns a one-shot definition whose attachment-scoped binding observes the fixture through ordinary frame phases.
     */
    fun definition(): UiDefinition
}
