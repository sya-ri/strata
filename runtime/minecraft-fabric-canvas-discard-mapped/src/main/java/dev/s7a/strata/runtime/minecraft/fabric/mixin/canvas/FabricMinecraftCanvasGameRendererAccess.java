package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Borrows the version-owned GUI renderer for synchronous queue cleanup before device destruction.
 *
 * <p>The generated accessor runs on the render thread and transfers no renderer or resource ownership.</p>
 */
@Mixin(GameRenderer.class)
@InternalStrataRuntimeApi
public interface FabricMinecraftCanvasGameRendererAccess {
    /**
     * Returns the current GUI consumer without submitting work or retaining it beyond the caller's operation.
     *
     * @return the native renderer whose pending draws must be discarded before Canvas targets are released.
     */
    @Accessor("guiRenderer")
    GuiRenderer strataCanvasGuiRenderer();
}
