package dev.s7a.strata.integration.minecraft.fabric.mixin;

import dev.s7a.strata.integration.minecraft.fabric.RemoteNativeServerFixture;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owns the isolated native test peer on the actual integrated-server tick and shutdown threads. */
@Mixin(MinecraftServer.class)
abstract class RemoteNativeServerLifecycleMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void strataTickRemoteFixture(CallbackInfo callback) {
        RemoteNativeServerFixture.INSTANCE.tick((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void strataStopRemoteFixture(CallbackInfo callback) {
        RemoteNativeServerFixture.INSTANCE.close();
    }
}
