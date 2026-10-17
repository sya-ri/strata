package dev.s7a.strata.runtime.minecraft.fabric.mixin.server;

import dev.s7a.strata.runtime.minecraft.fabric.FabricServerUiServices;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.OptionalInt;

/** Retires container-bound UI before another queued action can follow a native menu replacement. */
@Mixin(ServerPlayer.class)
abstract class FabricServerMenuMixin {
    @Inject(method = "openMenu", at = @At("RETURN"))
    private void strataOpenedMenu(CallbackInfoReturnable<OptionalInt> callback) {
        FabricServerUiServices.INSTANCE.containerChanged((ServerPlayer) (Object) this);
    }

    @Inject(method = "doCloseContainer", at = @At("TAIL"))
    private void strataClosedMenu(CallbackInfo callback) {
        FabricServerUiServices.INSTANCE.containerChanged((ServerPlayer) (Object) this);
    }
}
