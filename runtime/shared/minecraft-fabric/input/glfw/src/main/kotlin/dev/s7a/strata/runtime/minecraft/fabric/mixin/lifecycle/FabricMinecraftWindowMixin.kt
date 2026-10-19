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
    // Window installs native callbacks before Minecraft assigns its window field.
    @Suppress("KotlinConstantConditions", "RedundantNullableReturnType")
    @Inject(method = ["onFocus"], at = [At("TAIL")])
    private fun strataResetInput(
        window: Long,
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
