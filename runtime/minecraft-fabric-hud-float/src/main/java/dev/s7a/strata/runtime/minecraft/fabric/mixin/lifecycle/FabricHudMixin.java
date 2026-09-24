package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import dev.s7a.strata.runtime.minecraft.fabric.FabricUiSessions;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import kotlin.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws retained Strata overlays after vanilla HUD extraction and before ordinary screens. */
@Mixin(Gui.class)
abstract class FabricHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void strataHud(GuiGraphics graphics, float delta, CallbackInfo callback) {
        FabricUiSessions.INSTANCE.renderHuds((screen, mouseX, mouseY) -> {
            screen.render(graphics, mouseX, mouseY, 0.0f);
            return Unit.INSTANCE;
        });
    }
}
