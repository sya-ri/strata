package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Compiled independent native fixture selected by class name, without extending the canonical component case registry.
 * Case IDs and family are immutable external evidence identifiers; scene construction belongs to the client owner outside timing.
 * Implementations live in the same verified fixture archive as the native collector adapter.
 */
internal interface MinecraftNativePerformanceCorpus {
    /**
     * Stable evidence family distinct from canonical component and sampled-image acceptance.
     */
    val family: String

    /**
     * Complete compiled case matrix before optional shared workload selection.
     */
    val caseIds: Set<String>

    /**
     * Resolves one external case ID and prepares its bounded immutable inputs for the declared operation count.
     */
    fun scene(
        id: String,
        viewport: IntSize,
        operations: Int,
    ): MinecraftNativePerformanceScene
}
