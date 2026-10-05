package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Borrows allocated NativeImage storage during a synchronous render-thread upload copy. */
@Mixin(NativeImage.class)
public interface FabricMinecraftNativeImageAccess {
    /** Returns the allocation address without transferring ownership or extending its lifetime. */
    @Accessor("pixels")
    long strataPixels();
}
