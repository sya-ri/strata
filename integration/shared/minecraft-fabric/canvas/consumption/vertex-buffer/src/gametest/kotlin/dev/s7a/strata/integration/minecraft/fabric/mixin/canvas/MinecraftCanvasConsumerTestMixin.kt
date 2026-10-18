@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasCaptureTestHooks
import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasConsumerTestHooks
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.render.GuiRenderer
import org.spongepowered.asm.mixin.Mixin

/**
 * Runs the armed loaded-test callback immediately around the real native GUI consumer.
 *
 * The wrapper borrows its operation only for this client-thread invocation and propagates every consumer failure.
 * Nested consumers cannot take the already-cleared callback, and no production runtime includes this mixin.
 */
@Mixin(value = [GuiRenderer::class], priority = 1500)
private abstract class MinecraftCanvasConsumerTestMixin {
    @WrapMethod(method = ["render()V"])
    private fun strataTestCanvasConsumption(original: Operation<Void?>) {
        val after = MinecraftCanvasConsumerTestHooks.beforeConsumer()
        original.call()
        after?.run()
        MinecraftCanvasCaptureTestHooks.afterConsumer()
    }
}
