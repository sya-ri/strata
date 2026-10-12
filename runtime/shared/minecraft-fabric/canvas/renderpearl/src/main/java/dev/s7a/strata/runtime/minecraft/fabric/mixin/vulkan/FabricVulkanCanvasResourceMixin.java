package dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.s7a.strata.runtime.minecraft.fabric.FabricVulkanDestroyedResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Acknowledges a Vulkan image or view only after its actual native destruction succeeds.
 *
 * <p>The flag belongs to that native resource and is read only on the render thread.
 * Successful physical destruction makes a later queued dispatch inert; logical close does not set the flag.
 * No global map or Canvas ownership is introduced, and a failing destroy callback remains unacknowledged.</p>
 */
@Mixin({VulkanGpuTexture.class, VulkanGpuTextureView.class})
abstract class FabricVulkanCanvasResourceMixin implements FabricVulkanDestroyedResource {
    @Unique
    private boolean strata$canvasDestroyed;

    @Override
    public boolean strataCanvasResourceDestroyed() {
        return strata$canvasDestroyed;
    }

    /** Reports the host close request; this is not physical destruction acknowledgement. */
    @Shadow(remap = false)
    public abstract boolean isClosed();

    /** Dispatches the host native destructor on the render thread. */
    @Shadow(remap = false)
    public abstract void destroy();

    @Override
    public void strataDestroyCompletedResource() {
        RenderSystem.assertOnRenderThread();
        if (isClosed() == false) {
            throw new IllegalStateException("Completed Vulkan retirement requires a successful close request.");
        }
        if (strata$canvasDestroyed == false) {
            destroy();
        }
    }

    @Inject(method = "destroy()V", at = @At("HEAD"), cancellable = true)
    private void strata$skipAcknowledgedDestruction(CallbackInfo callback) {
        if (strata$canvasDestroyed) {
            callback.cancel();
        }
    }

    @Inject(method = "destroy()V", at = @At("RETURN"))
    private void strata$acknowledgeCanvasDestruction(CallbackInfo callback) {
        strata$canvasDestroyed = true;
    }
}
