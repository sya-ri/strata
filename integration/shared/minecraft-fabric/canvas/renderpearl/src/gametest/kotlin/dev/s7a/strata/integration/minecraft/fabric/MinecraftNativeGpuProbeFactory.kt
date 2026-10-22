package dev.s7a.strata.integration.minecraft.fabric

/**
 * Compiled timestamp-query constructor; obtaining it creates no render-thread resources.
 * Invoke the constructor only on the client render thread; the caller owns the resulting probe.
 */
internal val minecraftNativeGpuProbeFactory: ((Int) -> MinecraftNativeGpuProbe)? = ::MinecraftGpuPerformanceProbe
