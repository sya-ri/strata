package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Typed immutable sampling policy for the center of a Minecraft nine-slice sprite.
 *
 * Values are thread-safe, own no resource, and may be retained by immutable profiles and component descriptions.
 */
@InternalStrataRuntimeApi
public sealed interface MinecraftNineSliceCenterMode {
    /**
     * Repeats source-center pixels without interpolation and crops the last repetition when needed.
     */
    public data object Tiled : MinecraftNineSliceCenterMode

    /**
     * Scales the complete source center into the destination center with nearest sampling.
     */
    public data object Stretched : MinecraftNineSliceCenterMode
}
