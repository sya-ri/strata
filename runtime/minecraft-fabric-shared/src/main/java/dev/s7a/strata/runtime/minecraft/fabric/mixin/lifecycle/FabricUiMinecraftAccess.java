package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuses vanilla gameplay dispatch while a Strata wrapper owns the native screen. */
@Mixin(Minecraft.class)
public interface FabricUiMinecraftAccess {
    /** Runs the native hotbar, attack, use, drop, swap, and pick paths once per client tick. */
    @Invoker("handleKeybinds")
    void strataHandleKeys();

    /** Reads the native attack delay maintained across forwarded ticks. */
    @Accessor("missTime")
    int strataMissTime();

    /** Removes only the screen-imposed attack delay before native dispatch. */
    @Accessor("missTime")
    void strataMissTime(int ticks);
}
