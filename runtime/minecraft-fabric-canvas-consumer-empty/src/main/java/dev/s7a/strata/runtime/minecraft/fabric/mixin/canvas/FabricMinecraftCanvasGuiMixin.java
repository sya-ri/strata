package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasGuiCleanup;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiDiscard;
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import java.util.List;

/**
 * Runs the Canvas target-consumption boundary after the current GUI renderer has attempted to consume and encode its queue on either GPU backend.
 *
 * <p>This render-thread wrapper always reaches the shared lifetime hook after the actual consumer, including failures.
 * It never runs a producer or resolves a retained frame token, and it never retains the borrowed operation.
 * The hook preserves the original failure and does not block for unfinished GPU work.</p>
 */
@Mixin(GuiRenderer.class)
abstract class FabricMinecraftCanvasGuiMixin implements FabricMinecraftCanvasGuiDiscard {
    @Shadow @Final
    private List<?> draws;

    @Shadow @Final
    private StagedVertexBuffer vertexBuffer;

    @Shadow
    private TextureSetup previousTextureSetup;

    @Shadow
    private StagedVertexBuffer.Draw previousDraw;

    @Override
    public void strataDiscardCanvasGui() throws Throwable {
        FabricCanvasGuiCleanup.run(
                () -> {
                    draws.clear();
                    previousTextureSetup = null;
                    previousDraw = null;
                },
                () -> ((FabricMinecraftCanvasGuiDiscard) vertexBuffer).strataDiscardCanvasGui(),
                () -> ((FabricMinecraftCanvasRenderStateAccess) this).strataCanvasRenderState().reset());
    }

    @WrapMethod(method = "render()V")
    private void strata$consumeCanvasGui(Operation<Void> original) throws Throwable {
        Throwable failure = null;
        try {
            original.call();
        } catch (Throwable caught) {
            failure = caught;
            throw caught;
        } finally {
            FabricMinecraftCanvasHooks.afterGui(failure);
        }
    }
}
