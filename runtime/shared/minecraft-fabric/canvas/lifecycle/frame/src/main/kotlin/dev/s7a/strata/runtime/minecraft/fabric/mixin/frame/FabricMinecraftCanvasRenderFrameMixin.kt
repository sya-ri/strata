@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.frame

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin

/**
 * Handles native frame failures outside the regular client tick, including loading and disconnect frames.
 *
 * The render-thread hook never fences extraction. It stops an active Strata screen and discards pending GUI
 * references on failure while preserving the original failure, and polls independently owned resources after success.
 */
@Mixin(Minecraft::class)
private abstract class FabricMinecraftCanvasRenderFrameMixin {
    @WrapMethod(method = ["renderFrame(Z)V"])
    private fun strataHandleCanvasFrame(
        renderLevel: Boolean,
        original: Operation<Void?>,
    ) {
        val result = runCatching { original.call(renderLevel) }
        FabricMinecraftCanvasHooks.afterFrame(result.exceptionOrNull())
        result.getOrThrow()
    }
}
