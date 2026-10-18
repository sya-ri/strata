@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan

import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView
import dev.s7a.strata.runtime.minecraft.fabric.FabricVulkanDestroyedResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Acknowledges a Vulkan image or view only after its actual native destruction succeeds.
 *
 * The flag belongs to that native resource and is read only on the render thread.
 * No global map or Canvas ownership is introduced, and a failing destroy callback remains unacknowledged.
 */
@Mixin(value = [VulkanGpuTexture::class, VulkanGpuTextureView::class])
private abstract class FabricVulkanCanvasResourceMixin : FabricVulkanDestroyedResource {
    @field:Unique
    private var strataCanvasDestroyed = false

    override fun strataCanvasResourceDestroyed(): Boolean = strataCanvasDestroyed

    @Inject(method = ["destroy()V"], at = [At("RETURN")])
    private fun strataAcknowledgeCanvasDestruction(callback: CallbackInfo) {
        strataCanvasDestroyed = true
    }
}
