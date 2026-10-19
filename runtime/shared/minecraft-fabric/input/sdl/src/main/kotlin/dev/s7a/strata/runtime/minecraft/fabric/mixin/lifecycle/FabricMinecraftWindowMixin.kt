@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle

import com.mojang.blaze3d.platform.Window
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Cancels retained input after the native window records loss of focus.
 *
 * The callback runs on the client thread, retains no screen, and leaves ordinary native focus processing unchanged.
 */
@Mixin(Window::class)
private abstract class FabricMinecraftWindowMixin {
    @Suppress("KotlinConstantConditions", "RedundantNullableReturnType") // Preserve the original initialization checks despite native non-null annotations.
    @Inject(method = ["onFocus"], at = [At("TAIL")])
    private fun strataResetInput(
        focused: Boolean,
        callback: CallbackInfo,
    ) {
        val client: Minecraft? = Minecraft.getInstance()
        if (focused || client == null) return
        val nativeWindow: Window? = client.window
        if (nativeWindow == null || client.isWindowActive) return
        FabricMinecraftCanvasHooks.resetActiveInput(client)
    }
}
