package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s7a.strata.runtime.minecraft.fabric.FabricUiInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps native gameplay dispatch separate from native screen ticking and rendering. */
@Mixin(Minecraft.class)
abstract class FabricUiGameplayMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 10000))
    private int strataScreenAttackDelay(int original) {
        return FabricUiInput.INSTANCE.attackDelay(original);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void strataDispatchInput(CallbackInfo callback) {
        FabricUiInput.INSTANCE.tick();
    }

    @ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;"))
    private Screen strataGameplayScreen(Screen original) {
        return FabricUiInput.INSTANCE.getDispatching() ? null : original;
    }

    @ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean strataGameplayPointer(boolean original) {
        return original || FabricUiInput.INSTANCE.getDispatching();
    }
}
