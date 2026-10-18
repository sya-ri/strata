package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Version-owned capability for dropping queued GUI references without submitting native draws.
 * Native GUI owners implement this through typed Mixins; calls belong to the render thread and retain no resources after return.
 */
@InternalStrataRuntimeApi
public interface FabricMinecraftCanvasGuiDiscard {
    /**
     * Clears queued extraction state while leaving recorded or submitted GPU work owned until terminal device completion.
     * A failed independent cleanup does not acknowledge discard or permit Canvas target destruction.
     */
    @Throws(Throwable::class)
    public fun strataDiscardCanvasGui()
}
