package dev.s7a.strata.integration.minecraft.fabric.mixin.pacing;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes only the actual private legacy selector to client-owner performance fixtures, with normal production remapping. */
@InternalStrataRuntimeApi
@Mixin(Minecraft.class)
public interface MinecraftNativePerformanceClientAccess {
    /** Returns the native selector's current menu/window policy without duplicating or changing it. */
    @Invoker("getFramerateLimit")
    int strataNativeFramerateLimit();
}
