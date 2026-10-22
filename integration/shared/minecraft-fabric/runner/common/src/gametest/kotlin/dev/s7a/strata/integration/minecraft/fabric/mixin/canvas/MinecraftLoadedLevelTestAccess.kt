package dev.s7a.strata.integration.minecraft.fabric.mixin.canvas

import net.minecraft.client.multiplayer.ClientLevel
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import java.util.Deque

/**
 * Borrows the native world's pending light updates only to test client-thread rendering readiness.
 */
@Mixin(ClientLevel::class)
internal interface MinecraftLoadedLevelTestAccess {
    /**
     * Returns the client-owned queue for an immediate emptiness check without retaining or mutating it.
     */
    @Accessor("lightUpdateQueue")
    fun strataLightUpdates(): Deque<Runnable>
}
