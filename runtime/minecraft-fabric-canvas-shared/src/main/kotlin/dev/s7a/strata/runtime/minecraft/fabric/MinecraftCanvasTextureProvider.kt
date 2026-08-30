package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Supplies an externally owned native color image for an actual Canvas presentation.
 *
 * Calls run on the Minecraft render thread after final layout, at most once per attached canvas in a presentation batch.
 * A shared provider may be called once for each independently attached canvas.
 * Returning null preserves that canvas's last committed generation; the first missing image remains transparent.
 * Throwing aborts preparation without publishing partially captured pixels.
 */
public fun interface MinecraftCanvasTextureProvider {
    /**
     * Acquires stable RGBA8 straight-alpha contents without transferring ownership of the provider.
     *
     * @return a lease kept alive until the GPU capture fence completes, or null when no new image is available.
     * @throws Throwable when acquisition fails; partially acquired source resources remain the provider's responsibility.
     */
    public fun acquire(): MinecraftCanvasTextureLease?
}
