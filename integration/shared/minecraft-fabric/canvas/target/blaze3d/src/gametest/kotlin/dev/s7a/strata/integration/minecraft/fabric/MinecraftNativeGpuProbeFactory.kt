package dev.s7a.strata.integration.minecraft.fabric

/**
 * No native timestamp-query probe is available for this compiled backend.
 */
internal val minecraftNativeGpuProbeFactory: MinecraftNativeGpuProbeFactory =
    object : MinecraftNativeGpuProbeFactory {
        override val create: ((samples: Int) -> MinecraftNativeGpuProbe)? = null
    }
