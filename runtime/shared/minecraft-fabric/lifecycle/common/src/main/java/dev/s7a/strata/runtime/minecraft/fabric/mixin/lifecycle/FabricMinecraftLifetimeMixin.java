package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices;
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasShutdownTransaction;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiConsumption;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/**
 * Services device-owned canvas lifetimes across frames, resource reloads, and terminal renderer teardown.
 *
 * <p>No hook retains a screen. Polling never waits and never substitutes an extraction fence for actual GUI consumption.</p>
 */
@Mixin(Minecraft.class)
abstract class FabricMinecraftLifetimeMixin {
    @Unique
    private boolean strataCanvasShutdownActive;

    @WrapMethod(method = "runTick")
    private void strataPollCanvasLifetimes(boolean renderLevel, Operation<Void> original) throws Throwable {
        Throwable primary = null;
        try {
            original.call(renderLevel);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            FabricMinecraftCanvasHooks.afterFrame(primary);
        }
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void strataInvalidateCanvasResources(CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        NativeCanvasDevices.INSTANCE.reload();
    }

    @WrapMethod(method = "close")
    private void strataCloseCanvasDevice(Operation<Void> original) throws Throwable {
        if (strataCanvasShutdownActive) throw new IllegalStateException("Minecraft Canvas shutdown cannot reenter callbacks.");
        strataCanvasShutdownActive = true;
        Minecraft client = (Minecraft) (Object) this;
        try {
            FabricCanvasShutdownTransaction.run(
                    () -> {
                        FabricMinecraftCanvasHooks.beginShutdown();
                        FabricMinecraftCanvasHooks.closeActiveScreen(client);
                    },
                    () -> FabricMinecraftCanvasGuiConsumption.discardCanvasGui(client),
                    () -> NativeCanvasDevices.INSTANCE.closeAfterGuiDiscarded(),
                    () -> original.call());
        } finally {
            strataCanvasShutdownActive = false;
        }
    }
}
