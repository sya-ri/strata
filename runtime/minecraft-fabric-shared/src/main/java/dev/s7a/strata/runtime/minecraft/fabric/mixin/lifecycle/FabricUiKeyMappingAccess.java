package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads actual remapped keys and clears both held and queued native input at ownership boundaries. */
@Mixin(KeyMapping.class)
public interface FabricUiKeyMappingAccess {
    /** Returns the configured key, including scan-code and mouse bindings. */
    @Accessor("key")
    InputConstants.Key strataKey();

    /** Returns native queued presses. */
    @Accessor("clickCount")
    int strataClicks();

    /** Updates native queued presses without enabling another mapping bound to the same key. */
    @Accessor("clickCount")
    void strataClicks(int clicks);

    /** Clears held input directly, including toggle mappings. */
    @Accessor("isDown")
    void strataDown(boolean down);
}
