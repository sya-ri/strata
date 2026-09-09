package dev.s7a.strata.integration.minecraft.fabric

/**
 * Native presentation counters borrowed from each adapter's existing instrumentation.
 */
internal data class ReactiveNativeWork(
    val hostFrames: Long,
    val preparations: Long,
    val rasterizations: Long,
    val uploads: Long,
)
