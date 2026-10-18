package dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Reads actual remapped keys and clears both held and queued native input at ownership boundaries.
 */
@Mixin(KeyMapping::class)
public interface FabricUiKeyMappingAccess {
    /**
     * Returns the configured key, including scan-code and mouse bindings.
     */
    @Accessor("key")
    public fun strataKey(): InputConstants.Key

    /**
     * Returns native queued presses.
     */
    @Accessor("clickCount")
    public fun strataClicks(): Int

    /**
     * Updates native queued presses without enabling another mapping bound to the same key.
     */
    @Accessor("clickCount")
    public fun strataClicks(clicks: Int): Unit

    /**
     * Clears held input directly, including toggle mappings.
     */
    @Accessor("isDown")
    public fun strataDown(down: Boolean): Unit
}
