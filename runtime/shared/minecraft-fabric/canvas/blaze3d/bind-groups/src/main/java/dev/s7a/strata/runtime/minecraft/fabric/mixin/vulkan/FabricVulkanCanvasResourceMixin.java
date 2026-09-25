package dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.s7a.strata.runtime.minecraft.fabric.FabricVulkanDestroyedResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Acknowledges a Vulkan image or view only after its actual native destruction succeeds.
 *
 * <p>The flag belongs to that native resource and is read only on the render thread.
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

    @Inject(method = "destroy()V", at = @At("RETURN"))
    private void strata$acknowledgeCanvasDestruction(CallbackInfo callback) {
        strata$canvasDestroyed = true;
    }
}
