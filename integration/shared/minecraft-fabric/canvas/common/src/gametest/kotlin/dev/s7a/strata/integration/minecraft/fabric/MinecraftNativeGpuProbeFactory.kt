package dev.s7a.strata.integration.minecraft.fabric

/**
 * Exposes the compiled backend's optional timestamp-query constructor without creating native resources.
 * Invoke an available constructor only on the client render thread with a positive sample capacity; the caller owns and closes the resulting probe.
 */
internal interface MinecraftNativeGpuProbeFactory {
    /**
     * Returns the supported constructor, or null when this backend has no timestamp-query probe.
     */
    val create: ((samples: Int) -> MinecraftNativeGpuProbe)?
}
