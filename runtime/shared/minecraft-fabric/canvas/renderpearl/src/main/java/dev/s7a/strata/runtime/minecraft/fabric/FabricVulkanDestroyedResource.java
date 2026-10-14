package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;

/**
 * Exposes physical destruction of a Vulkan texture or view without exposing its native handle.
 *
 * <p>Only the render thread queries this capability, which is supplied by the version-owned native mixin.
 * The owning Canvas target retains the resource until the permanent acknowledgement becomes true.</p>
 */
@InternalStrataRuntimeApi
public interface FabricVulkanDestroyedResource {
    /**
     * Reports successful completion of the native destroy callback, not merely a queued close request.
     *
     * @return true only after native storage was destroyed; this nonblocking query never changes ownership.
     */
    boolean strataCanvasResourceDestroyed();
    /**
     * Physically destroys this closed, exclusively owned resource after initialization and every GUI use have completed.
     *
     * <p>The render-thread caller must establish GPU completion independently before this call.
     * Logical close is only an acquisition prerequisite; acknowledgement still comes from successful native destruction.
     * A later queued native destroy is idempotent after that acknowledgement, and an unsuccessful destroy remains owned.</p>
     */
    void strataDestroyCompletedResource();
}
