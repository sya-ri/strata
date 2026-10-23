package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas

import com.mojang.blaze3d.platform.Window
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.gen.Invoker

/**
 * Exposes verified Window access only to loaded-client Canvas acceptance tests.
 *
 * Callers borrow the active window on its client thread either to update a logical Vulkan test viewport without resizing the native surface or to invoke its native focus callback.
 * The focus callback updates native state before the production reset hook runs when the test harness permits it.
 * The test-only invocation scope preserves Fabric's ordinary cancellation and permits only its explicit focus call.
 * No operating-system focus change is synthesized, and failures propagate unchanged.
 */
@InternalStrataRuntimeApi
@Mixin(Window::class)
public interface MinecraftCanvasWindowTestAccess {
    /**
     * Updates this borrowed window's logical viewport width without changing its native surface.
     *
     * @param width positive logical width owned by the loaded-client test until restoration
     */
    @Accessor("width")
    public fun strataCanvasSetScreenWidth(width: Int)

    /**
     * Updates this borrowed window's logical viewport height without changing its native surface.
     *
     * @param height positive logical height owned by the loaded-client test until restoration
     */
    @Accessor("height")
    public fun strataCanvasSetScreenHeight(height: Int)

    /**
     * Invokes the SDL window focus callback with the supplied state.
     *
     * @param focused new native focus state, restored by the caller after the test
     */
    @Invoker("onFocus")
    public fun strataCanvasFocus(focused: Boolean)

    /**
     * Updates the test framebuffer width without resizing the SDL surface.
     */
    @Accessor("framebufferWidth")
    public fun strataCanvasSetFramebufferWidth(width: Int)

    /**
     * Updates the test framebuffer height without resizing the SDL surface.
     */
    @Accessor("framebufferHeight")
    public fun strataCanvasSetFramebufferHeight(height: Int)
}
