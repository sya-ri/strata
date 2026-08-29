package dev.s7a.strata.runtime.minecraft.fabric.mixin.vulkan;

import com.mojang.blaze3d.vulkan.DestructionQueue;
import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Borrows the shared Vulkan destruction queue after terminal GPU completion has been established.
 *
 * <p>The render-thread caller may drain this queue only after all pending GUI work was discarded or consumed,
 * the device was finished, and Canvas resource closes were requested. The queue is never replaced or retained.</p>
 */
@InternalStrataRuntimeApi
@Mixin(VulkanCommandEncoder.class)
public interface FabricVulkanCanvasEncoderAccessor {
    /**
     * Returns the existing deferred-destruction queue without performing native work.
     *
     * @return the queue borrowed for the current terminal cleanup callback only.
     */
    @Accessor("destroyQueue")
    DestructionQueue<Destroyable> strataCanvasDestructionQueue();
}
