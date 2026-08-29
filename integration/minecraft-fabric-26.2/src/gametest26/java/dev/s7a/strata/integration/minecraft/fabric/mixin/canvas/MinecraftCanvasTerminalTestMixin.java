package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasTerminalTestHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Observes actual 26.2 client shutdown outside the production Canvas lifetime wrapper.
 *
 * <p>The armed observer verifies wrapper order before queuing native work and always calls original close,
 * including when setup fails. Original shutdown failures remain primary, with setup failures suppressed.
 * Successful verification runs after vanilla has destroyed its GPU device and therefore uses no native API.</p>
 */
@Mixin(value = Minecraft.class, priority = 1500)
abstract class MinecraftCanvasTerminalTestMixin {
    @WrapMethod(method = "close")
    private void strata$verifyTerminalCanvasRelease(Operation<Void> original) throws Throwable {
        Throwable setupFailure = null;
        Runnable verify = null;
        try {
            verify = MinecraftCanvasTerminalTestHooks.beforeClose((Minecraft) (Object) this);
        } catch (Throwable failure) {
            setupFailure = failure;
        }
        try {
            original.call();
        } catch (Throwable failure) {
            if (setupFailure != null && setupFailure != failure) failure.addSuppressed(setupFailure);
            throw failure;
        }
        if (setupFailure != null) throw setupFailure;
        if (verify != null) verify.run();
    }
}
