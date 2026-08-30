package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas;

import com.mojang.blaze3d.platform.Window;
import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes verified Window access only to loaded-client Canvas acceptance tests.
 *
 * <p>Callers borrow the active window on its client thread either to update a logical Vulkan test viewport without resizing the native surface or to supply the actual handle to the focus callback.
 * The focus callback updates native state before the production reset hook runs when the test harness permits it.
 * The test-only invocation scope preserves Fabric's ordinary cancellation and permits only its explicit focus call.
 * No operating-system focus change is synthesized, and failures propagate unchanged.</p>
 */
@InternalStrataRuntimeApi
@Mixin(Window.class)
public interface MinecraftCanvasWindowTestAccess {
    /**
     * Updates this borrowed window's logical viewport width without changing its native surface.
     *
     * @param width positive logical width owned by the loaded-client test until restoration
     */
    @Accessor("width")
    void strataCanvasSetScreenWidth(int width);

    /**
     * Updates this borrowed window's logical viewport height without changing its native surface.
     *
     * @param height positive logical height owned by the loaded-client test until restoration
     */
    @Accessor("height")
    void strataCanvasSetScreenHeight(int height);

    /**
     * Invokes the native focus callback with the supplied window-owned handle.
     *
     * @param window handle of this borrowed native window
     * @param focused new native focus state, restored by the caller after the test
     */
    @Invoker("onFocus")
    void strataCanvasFocus(long window, boolean focused);
}
