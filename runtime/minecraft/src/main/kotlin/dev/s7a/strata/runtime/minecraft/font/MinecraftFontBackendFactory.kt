package dev.s7a.strata.runtime.minecraft.font

/**
 * Creates independent owner-thread CPU backends without retaining a game or graphics context.
 */
public fun interface MinecraftFontBackendFactory {
    /**
     * Opens an engine-owned backend matching the selected release capabilities.
     * A failed open must release every partially allocated native resource before propagating the failure.
     *
     * @param compatibility immutable resource and rasterization capabilities.
     * @return a new backend confined to the calling thread.
     * @throws Throwable when the requested native rasterizer is unavailable.
     */
    public fun open(compatibility: MinecraftFontCompatibility): MinecraftFontBackend
}
