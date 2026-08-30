package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import java.util.ArrayList;
import java.util.List;

/**
 * Attempts independent GUI queue cleanup without skipping later reference release after a failure.
 *
 * <p>Operations and queues are borrowed synchronously on the render thread. This helper retains no callback,
 * native handle, or queue after returning and never waits for GPU work.</p>
 */
@InternalStrataRuntimeApi
public final class FabricCanvasGuiCleanup {
    private FabricCanvasGuiCleanup() {
    }

    /**
     * Attempts every cleanup action and preserves the first failure with later failures suppressed.
     *
     * @param cleanups borrowed cleanup operations, never retained or retried by this helper.
     * @throws Throwable after all operations have been attempted when any cleanup fails.
     */
    public static void run(Cleanup... cleanups) throws Throwable {
        Throwable primary = null;
        for (Cleanup cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (Throwable failure) {
                if (primary == null) primary = failure;
                else FabricMinecraftFailures.addSuppressed(primary, failure);
            }
        }
        if (primary != null) throw primary;
    }

    /**
     * Removes queued mesh references before closing their independently owned CPU vertex results.
     *
     * <p>The native mesh records implement {@link AutoCloseable}; their result close operations are idempotent,
     * including records already processed before a partial GUI failure. No GPU buffers are closed here.</p>
     *
     * @param meshes mutable GUI mesh queue, emptied before the first close callback.
     * @throws Throwable after every mesh close has been attempted if any result release fails.
     */
    public static void closeMeshes(List<? extends AutoCloseable> meshes) throws Throwable {
        List<AutoCloseable> pending = new ArrayList<>(meshes);
        meshes.clear();
        run(pending.stream().<Cleanup>map(mesh -> mesh::close).toArray(Cleanup[]::new));
    }

    /**
     * Borrows one synchronous owner-thread cleanup operation without transferring resource ownership.
     */
    @FunctionalInterface
    @InternalStrataRuntimeApi
    public interface Cleanup {
        /**
         * Attempts the supplied independent cleanup, propagating its original failure unchanged.
         *
         * @throws Throwable when the borrowed cleanup fails.
         */
        void run() throws Throwable;
    }
}
