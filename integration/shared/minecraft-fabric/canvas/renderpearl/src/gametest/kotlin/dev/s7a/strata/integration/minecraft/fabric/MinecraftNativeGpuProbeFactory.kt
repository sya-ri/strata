package dev.s7a.strata.integration.minecraft.fabric

/**
 * Supplies the RenderPearl constructor through the shared probe-ownership contract.
 */
internal val minecraftNativeGpuProbeFactory: MinecraftNativeGpuProbeFactory =
    object : MinecraftNativeGpuProbeFactory {
        override val create = ::MinecraftGpuPerformanceProbe
    }
