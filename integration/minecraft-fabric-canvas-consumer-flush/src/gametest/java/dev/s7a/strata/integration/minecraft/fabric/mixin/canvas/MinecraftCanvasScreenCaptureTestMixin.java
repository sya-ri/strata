package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas;

import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasCaptureTestHooks;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures an immediate-family Canvas only after its whole native screen submission and final GUI flush have returned.
 *
 * <p>Unlike an arbitrary intermediate GuiGraphics.flush, this boundary follows publication of the same presentation receipt.
 * The borrowed native graphics and screen are never retained by this test-only client-thread hook.</p>
 */
@Mixin(Screen.class)
abstract class MinecraftCanvasScreenCaptureTestMixin {
    @Inject(method = "renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"))
    private void strata$testCanvasCapture(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if ((Object) this instanceof FabricMinecraftScreen) {
            MinecraftCanvasCaptureTestHooks.afterConsumer();
        }
    }
}
