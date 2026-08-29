package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the mapped family's typed extraction state only for owner-thread queue cleanup.
 *
 * <p>The state remains owned by Minecraft and is never retained by Strata after the cleanup call.</p>
 */
@Mixin(GuiRenderer.class)
@InternalStrataRuntimeApi
public interface FabricMinecraftCanvasRenderStateAccess {
    /**
     * Returns the extracted GUI queue whose native texture references must be removed before target destruction.
     *
     * @return the mutable Minecraft-owned render state, borrowed on the render thread.
     */
    @Accessor("renderState")
    GuiRenderState strataCanvasRenderState();
}
