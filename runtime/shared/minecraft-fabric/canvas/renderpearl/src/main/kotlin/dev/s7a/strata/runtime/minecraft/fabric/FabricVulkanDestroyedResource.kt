package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Exposes physical Vulkan texture or view destruction without exposing a native handle.
 * The render thread borrows this Mixin-provided capability while its Canvas owner retains the resource until acknowledgement.
 */
@InternalStrataRuntimeApi
public interface FabricVulkanDestroyedResource {
    /**
     * Reports completed native destruction rather than a queued close request, without blocking or changing ownership.
     */
    public fun strataCanvasResourceDestroyed(): Boolean

    /**
     * Physically destroys an exclusively owned closed resource only after the caller independently establishes GPU completion.
     * A later queued destroy is idempotent after acknowledgement; failed destruction remains owned.
     */
    public fun strataDestroyCompletedResource()
}
