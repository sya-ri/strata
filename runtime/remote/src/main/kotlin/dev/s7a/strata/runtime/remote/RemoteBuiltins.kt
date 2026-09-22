package dev.s7a.strata.runtime.remote

/**
 * Standard codec registration shared by every versioned Minecraft client adapter.
 */
public object RemoteBuiltins {
    /**
     * Adds supported standard declarations before callers register their own extension schemas.
     */
    public fun register(registry: RemoteRegistry) {
        RemoteLayouts.register(registry)
        RemoteModifiers.register(registry)
        RemoteInputModifiers.register(registry)
        RemoteInputSubscriptions.register(registry)
        RemoteProfileComponents.register(registry)
        RemoteForms.register(registry)
        RemoteCanvas.registerPixels(registry)
        val transform = RemotePanZoom()
        transform.register(registry)
        RemoteTiledImages(transform).register(registry)
    }
}
