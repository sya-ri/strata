package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;

/**
 * Version-owned capability for discarding queued GUI references without submitting native draws.
 *
 * <p>The GUI owner implements this interface through a typed mixin.
 * Calls belong to the render thread and borrow no resources after returning.</p>
 */
@InternalStrataRuntimeApi
public interface FabricMinecraftCanvasGuiDiscard {
    /**
     * Clears queued draw and extraction state while preserving recorded or submitted GPU work for terminal device completion.
     *
     * @throws Throwable when independent cleanup fails; callers must not assume discard completed or destroy Canvas targets.
     */
    void strataDiscardCanvasGui() throws Throwable;
}
