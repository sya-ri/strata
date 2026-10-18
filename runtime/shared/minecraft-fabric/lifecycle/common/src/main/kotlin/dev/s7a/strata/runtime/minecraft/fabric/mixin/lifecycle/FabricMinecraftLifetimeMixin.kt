@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasShutdownTransaction
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks
import dev.s7a.strata.runtime.minecraft.fabric.discardCanvasGui
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.concurrent.CompletableFuture

/**
 * Services device-owned canvas lifetimes across frames, resource reloads, and terminal renderer teardown.
 *
 * No hook retains a screen. Polling never waits and never substitutes an extraction fence for actual GUI consumption.
 */
@Mixin(Minecraft::class)
private abstract class FabricMinecraftLifetimeMixin {
    @field:Unique
    private var strataCanvasShutdownActive = false

    @WrapMethod(method = ["runTick"])
    private fun strataPollCanvasLifetimes(
        renderLevel: Boolean,
        original: Operation<Void?>,
    ) {
        val result = runCatching { original.call(renderLevel) }
        FabricMinecraftCanvasHooks.afterFrame(result.exceptionOrNull())
        result.getOrThrow()
    }

    @Inject(method = ["reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;"], at = [At("HEAD")])
    private fun strataInvalidateCanvasResources(callback: CallbackInfoReturnable<CompletableFuture<Void>>) {
        NativeCanvasDevices.reload()
    }

    @WrapMethod(method = ["close"])
    private fun strataCloseCanvasDevice(original: Operation<Void?>) {
        check(strataCanvasShutdownActive.not()) { "Minecraft Canvas shutdown cannot reenter callbacks." }
        strataCanvasShutdownActive = true
        val client = Minecraft::class.java.cast(this)
        try {
            FabricCanvasShutdownTransaction.run(
                {
                    FabricMinecraftCanvasHooks.beginShutdown()
                    FabricMinecraftCanvasHooks.closeActiveScreen(client)
                },
                { discardCanvasGui(client) },
                { NativeCanvasDevices.closeAfterGuiDiscarded() },
                { original.call() },
            )
        } finally {
            strataCanvasShutdownActive = false
        }
    }
}
