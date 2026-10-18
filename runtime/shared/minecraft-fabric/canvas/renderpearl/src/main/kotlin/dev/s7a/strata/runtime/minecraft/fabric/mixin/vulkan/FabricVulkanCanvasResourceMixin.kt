@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import dev.s7a.strata.runtime.minecraft.fabric.FabricVulkanDestroyedResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Acknowledges a Vulkan image or view only after its actual native destruction succeeds.
 *
 * The flag belongs to that native resource and is read only on the render thread.
 * Successful physical destruction makes a later queued dispatch inert; logical close does not set the flag.
 * No global map or Canvas ownership is introduced, and a failing destroy callback remains unacknowledged.
 */
@Mixin(value = [VulkanGpuTexture::class, VulkanGpuTextureView::class])
private abstract class FabricVulkanCanvasResourceMixin : FabricVulkanDestroyedResource {
    @field:Unique
    private var strataCanvasDestroyed = false

    override fun strataCanvasResourceDestroyed(): Boolean = strataCanvasDestroyed

    /**
     * Reports the host close request without acknowledging physical destruction.
     */
    @Shadow(remap = false)
    abstract fun isClosed(): Boolean

    /**
     * Dispatches the host native destructor on the render thread.
     */
    @Shadow(remap = false)
    abstract fun destroy()

    override fun strataDestroyCompletedResource() {
        RenderSystem.assertOnRenderThread()
        check(isClosed()) { "Completed Vulkan retirement requires a successful close request." }
        if (strataCanvasDestroyed.not()) destroy()
    }

    @Inject(method = ["destroy()V"], at = [At("HEAD")], cancellable = true)
    private fun strataSkipAcknowledgedDestruction(callback: CallbackInfo) {
        if (strataCanvasDestroyed) callback.cancel()
    }

    @Inject(method = ["destroy()V"], at = [At("RETURN")])
    private fun strataAcknowledgeCanvasDestruction(callback: CallbackInfo) {
        strataCanvasDestroyed = true
    }
}
