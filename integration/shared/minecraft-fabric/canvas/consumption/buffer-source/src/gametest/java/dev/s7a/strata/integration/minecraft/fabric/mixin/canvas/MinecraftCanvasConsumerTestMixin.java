package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasCaptureTestHooks;
import dev.s7a.strata.integration.minecraft.fabric.MinecraftCanvasConsumerTestHooks;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Runs the armed loaded-test callback immediately around the real native GUI consumer.
 *
 * <p>The wrapper borrows its operation only for this client-thread invocation and propagates every consumer failure.
 * Nested consumers cannot take the already-cleared callback, and no production runtime includes this mixin.</p>
 */
@Mixin(value = GuiRenderer.class, priority = 1500)
abstract class MinecraftCanvasConsumerTestMixin {
    @WrapMethod(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")
    private void strata$testCanvasConsumption(GpuBufferSlice fog, Operation<Void> original) {
        Runnable after = MinecraftCanvasConsumerTestHooks.beforeConsumer();
        original.call(fog);
        if (after != null) {
            after.run();
        }
        MinecraftCanvasCaptureTestHooks.afterConsumer();
    }
}
