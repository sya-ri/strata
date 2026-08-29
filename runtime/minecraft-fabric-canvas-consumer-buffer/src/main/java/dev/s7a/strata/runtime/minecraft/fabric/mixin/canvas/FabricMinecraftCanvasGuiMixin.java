package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasGuiCleanup;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiDiscard;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;

/**
 * Records Canvas target consumption after the queued GUI renderer has issued its GPU work.
 *
 * <p>This render-thread boundary runs after extraction and producer capture, even when GUI rendering throws.
 * The shared hook fences successful consumption or quarantines failed queues without waiting.
 * The borrowed projection buffer and operation are never retained, and cleanup preserves any original rendering failure.</p>
 */
@Mixin(GuiRenderer.class)
abstract class FabricMinecraftCanvasGuiMixin implements FabricMinecraftCanvasGuiDiscard {
    @Shadow @Final
    private List<?> draws;

    @Shadow @Final
    private List<? extends AutoCloseable> meshesToDraw;

    @Shadow @Final
    private ByteBufferBuilder byteBufferBuilder;

    @Shadow
    private TextureSetup previousTextureSetup;

    @Shadow
    private BufferBuilder bufferBuilder;

    @Override
    public void strataDiscardCanvasGui() throws Throwable {
        FabricCanvasGuiCleanup.run(
                () -> {
                    draws.clear();
                    previousTextureSetup = null;
                    bufferBuilder = null;
                },
                () -> FabricCanvasGuiCleanup.closeMeshes(meshesToDraw),
                () -> {
                    ByteBufferBuilder.Result abandoned = byteBufferBuilder.build();
                    if (abandoned != null) abandoned.close();
                },
                byteBufferBuilder::discard,
                () -> ((FabricMinecraftCanvasRenderStateAccess) this).strataCanvasRenderState().reset());
    }

    @WrapMethod(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")
    private void strata$consumeCanvasGui(GpuBufferSlice projection, Operation<Void> original) throws Throwable {
        Throwable failure = null;
        try {
            original.call(projection);
        } catch (Throwable caught) {
            failure = caught;
            throw caught;
        } finally {
            FabricMinecraftCanvasHooks.afterGui(failure);
        }
    }
}
