package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasGuiCleanup;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiDiscard;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Discards pending GUI vertex data without closing any native GPU buffer or advancing a GPU buffer pool.
 *
 * <p>The GUI consumer calls this capability only on its own staged buffer, on the render thread, after a failure
 * or before terminal device completion. It retains no drawing context and never submits GPU work.</p>
 */
@Mixin(StagedVertexBuffer.class)
abstract class FabricMinecraftCanvasVertexBufferMixin implements FabricMinecraftCanvasGuiDiscard {
    @Shadow @Final
    private ByteBufferBuilder stagingBuffer;

    @Shadow
    private BufferBuilder lastVertexBuilder;

    @Shadow
    private StagedVertexBuffer.Draw lastBuildingDraw;

    /**
     * Clears staged draw references while leaving the GPU buffer pools owned by Minecraft.
     */
    @Shadow
    public abstract void endDraw();

    @Override
    public void strataDiscardCanvasGui() throws Throwable {
        lastVertexBuilder = null;
        lastBuildingDraw = null;
        FabricCanvasGuiCleanup.run(
                this::endDraw,
                () -> {
                    ByteBufferBuilder.Result abandoned = stagingBuffer.build();
                    if (abandoned != null) abandoned.close();
                },
                stagingBuffer::discard);
    }
}
