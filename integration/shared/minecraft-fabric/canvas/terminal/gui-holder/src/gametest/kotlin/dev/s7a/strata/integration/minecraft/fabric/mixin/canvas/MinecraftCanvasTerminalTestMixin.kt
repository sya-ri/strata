package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasTerminalTestHooks
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin

/**
 * Observes actual client shutdown outside the production Canvas lifetime wrapper.
 *
 * The armed observer verifies wrapper order before queuing native work and always calls original close,
 * including when setup fails. Original shutdown failures remain primary, with setup failures suppressed.
 * Successful verification runs after vanilla has destroyed its GPU device and therefore uses no native API.
 */
@Mixin(value = [Minecraft::class], priority = 1500)
private abstract class MinecraftCanvasTerminalTestMixin {
    @WrapMethod(method = ["close"])
    private fun strataVerifyTerminalCanvasRelease(original: Operation<Void?>) {
        val setup = runCatching { MinecraftCanvasTerminalTestHooks.beforeClose(Minecraft::class.java.cast(this)) }
        val closing = runCatching { original.call() }
        val failure = closing.exceptionOrNull()
        val setupFailure = setup.exceptionOrNull()
        if (failure != null) {
            if (setupFailure != null && setupFailure !== failure) failure.addSuppressed(setupFailure)
            throw failure
        }
        setup.getOrThrow()?.run()
    }
}
