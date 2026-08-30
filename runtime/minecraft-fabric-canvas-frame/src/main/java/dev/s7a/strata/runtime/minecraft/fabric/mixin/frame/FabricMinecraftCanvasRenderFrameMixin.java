package dev.s7a.strata.runtime.minecraft.fabric.mixin.frame;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Handles native frame failures outside the regular client tick, including loading and disconnect frames.
 *
 * <p>The render-thread hook never fences extraction. It stops an active Strata screen and discards pending GUI
 * references on failure while preserving the original failure, and polls independently owned resources after success.</p>
 */
@Mixin(Minecraft.class)
abstract class FabricMinecraftCanvasRenderFrameMixin {
    @WrapMethod(method = "renderFrame(Z)V")
    private void strataHandleCanvasFrame(boolean renderLevel, Operation<Void> original) throws Throwable {
        Throwable primary = null;
        try {
            original.call(renderLevel);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            FabricMinecraftCanvasHooks.afterFrame(primary);
        }
    }
}
