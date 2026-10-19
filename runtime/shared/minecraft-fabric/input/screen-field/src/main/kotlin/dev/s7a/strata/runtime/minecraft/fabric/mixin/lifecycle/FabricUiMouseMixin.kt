@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition
import dev.s7a.strata.runtime.minecraft.fabric.FabricUiInput
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.screens.Screen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Preserves the retained wrapper during mouse capture and uses native camera/scroll processing.
 */
@Mixin(MouseHandler::class)
private abstract class FabricUiMouseMixin {
    @Inject(method = ["onScroll"], at = [At("HEAD")])
    private fun strataBeginScroll(
        window: Long,
        horizontal: Double,
        vertical: Double,
        callback: CallbackInfo,
    ) {
        FabricUiInput.beginScroll(window, horizontal, vertical)
    }

    @Inject(method = ["onScroll"], at = [At("RETURN")])
    private fun strataEndScroll(
        window: Long,
        horizontal: Double,
        vertical: Double,
        callback: CallbackInfo,
    ) {
        FabricUiInput.endScroll()
    }

    @WrapWithCondition(method = ["grabMouse"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V")])
    private fun strataKeepWrapper(
        owner: Minecraft,
        next: Screen?,
    ): Boolean = FabricUiInput.capturing.not()

    @WrapWithCondition(method = ["grabMouse"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setAll()V")])
    private fun strataKeepReleasedKeys(): Boolean = FabricUiInput.capturing.not()

    @ModifyExpressionValue(method = ["onScroll"], at = [At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;screen:Lnet/minecraft/client/gui/screens/Screen;")])
    private fun strataScrollScreen(original: Screen?): Screen? = if (FabricUiInput.scrolling) null else original

    @Inject(method = ["turnPlayer"], at = [At("HEAD")], cancellable = true)
    private fun strataCameraPermission(callback: CallbackInfo) {
        if (FabricUiInput.blocksLook()) callback.cancel()
    }
}
