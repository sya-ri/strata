package dev.s7a.strata.runtime.minecraft.fabric.mixin.remote;

import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteScreens;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Services queued remote screen messages after each native client tick.
 */
@Mixin(Minecraft.class)
abstract class FabricRemoteTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void strataRemoteTick(CallbackInfo callback) {
        FabricRemoteScreens.INSTANCE.tick();
    }
}
