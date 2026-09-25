package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuses native wheel accumulation, hotbar selection, and spectator behavior. */
@Mixin(MouseHandler.class)
public interface FabricUiMouseAccess {
    /** Dispatches an unconsumed physical scroll through the original native handler. */
    @Invoker("onScroll")
    void strataScroll(long window, double horizontal, double vertical);
}
