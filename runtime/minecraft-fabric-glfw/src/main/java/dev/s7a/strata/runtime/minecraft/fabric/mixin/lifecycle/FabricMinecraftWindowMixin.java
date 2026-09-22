package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import com.mojang.blaze3d.platform.Window;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels retained input after the native window records loss of focus.
 *
 * <p>The callback runs on the client thread, retains no screen, and leaves ordinary native focus processing unchanged.</p>
 */
@Mixin(Window.class)
abstract class FabricMinecraftWindowMixin {
    @Inject(method = "onFocus", at = @At("TAIL"))
    private void strataResetInput(long window, boolean focused, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (focused || client == null || client.getWindow() == null || client.isWindowActive()) return;
        FabricMinecraftCanvasHooks.resetActiveInput(client);
    }
}
