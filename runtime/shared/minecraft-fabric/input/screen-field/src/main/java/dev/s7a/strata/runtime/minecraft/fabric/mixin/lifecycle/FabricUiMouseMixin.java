package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.s7a.strata.runtime.minecraft.fabric.FabricUiInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserves the retained wrapper during mouse capture and uses native camera/scroll processing. */
@Mixin(MouseHandler.class)
abstract class FabricUiMouseMixin {
    @Inject(method = "onScroll", at = @At("HEAD"))
    private void strataBeginScroll(long window, double horizontal, double vertical, CallbackInfo callback) {
        FabricUiInput.INSTANCE.beginScroll(window, horizontal, vertical);
    }

    @Inject(method = "onScroll", at = @At("RETURN"))
    private void strataEndScroll(long window, double horizontal, double vertical, CallbackInfo callback) {
        FabricUiInput.INSTANCE.endScroll();
    }

    @WrapWithCondition(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private boolean strataKeepWrapper(Minecraft owner, Screen next) {
        return FabricUiInput.INSTANCE.getCapturing() == false;
    }

    @WrapWithCondition(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setAll()V"))
    private boolean strataKeepReleasedKeys() {
        return FabricUiInput.INSTANCE.getCapturing() == false;
    }

    @ModifyExpressionValue(method = "onScroll", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;screen:Lnet/minecraft/client/gui/screens/Screen;"))
    private Screen strataScrollScreen(Screen original) {
        return FabricUiInput.INSTANCE.getScrolling() ? null : original;
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void strataCameraPermission(CallbackInfo callback) {
        if (FabricUiInput.INSTANCE.blocksLook()) callback.cancel();
    }
}
