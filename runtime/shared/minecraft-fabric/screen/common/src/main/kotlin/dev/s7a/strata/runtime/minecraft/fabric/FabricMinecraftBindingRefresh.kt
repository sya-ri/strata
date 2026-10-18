package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Polls bindings admitted at entry while allowing earlier owner-thread callbacks to dispose bindings or close the platform.
 * Removed bindings are skipped, newly admitted bindings wait for the next poll, and callback failures escape unchanged.
 * The membership snapshot and callback are borrowed only for this synchronous call and are never retained.
 */
internal fun <T> refreshMinecraftBindings(
    bindings: Set<T>,
    refresh: (T) -> Unit,
) {
    for (binding in bindings.toList()) {
        if (binding in bindings) refresh(binding)
    }
}
