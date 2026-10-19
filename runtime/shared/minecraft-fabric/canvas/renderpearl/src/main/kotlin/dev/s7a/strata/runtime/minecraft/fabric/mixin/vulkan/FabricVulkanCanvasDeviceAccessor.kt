package dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan

import com.mojang.renderpearl.backend.api.GpuDeviceBackend
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Borrows the device backend solely for terminal Canvas retirement cleanup.
 *
 * The render-thread caller never retains or replaces the returned backend.
 * Ordinary frame capture and drawing use the public backend-neutral GPU API.
 */
@InternalStrataRuntimeApi
@Mixin(FrontendGpuDevice::class)
public interface FabricVulkanCanvasDeviceAccessor {
    /**
     * Returns the existing backend without allocation, submission, or ownership transfer.
     *
     * @return the live backend, borrowed until the terminal cleanup callback returns.
     */
    @Accessor("backend")
    public fun strataCanvasBackend(): GpuDeviceBackend
}
