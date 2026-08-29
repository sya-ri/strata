package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas;

import com.mojang.blaze3d.platform.Window;
import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the real native focus callback only to loaded-client input acceptance tests.
 *
 * <p>Callers borrow the active window on its client thread and supply its actual handle.
 * The callback updates native focus state before the production reset hook runs when the test harness permits it.
 * The test-only invocation scope preserves Fabric's ordinary cancellation and permits only its explicit focus call.
 * No operating-system focus change is synthesized, and callback failures propagate unchanged.</p>
 */
@InternalStrataRuntimeApi
@Mixin(Window.class)
public interface MinecraftCanvasWindowTestAccess {
    /**
     * Invokes the native focus callback with the supplied window-owned handle.
     *
     * @param window handle of this borrowed native window
     * @param focused new native focus state, restored by the caller after the test
     */
    @Invoker("onFocus")
    void strataCanvasFocus(long window, boolean focused);
}
