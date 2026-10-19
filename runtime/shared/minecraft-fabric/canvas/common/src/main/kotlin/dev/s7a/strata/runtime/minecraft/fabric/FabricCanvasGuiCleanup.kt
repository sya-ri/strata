package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Attempts independent render-thread GUI cleanup without retaining callbacks, handles or queues after return.
 * Every cleanup is attempted even when an earlier one fails, without waiting for GPU work.
 */
@InternalStrataRuntimeApi
public object FabricCanvasGuiCleanup {
    /**
     * Runs every borrowed cleanup once and preserves the first failure with later distinct failures suppressed.
     */
    @JvmStatic
    @Throws(Throwable::class)
    public fun run(vararg cleanups: Cleanup) {
        var primary: Throwable? = null
        for (cleanup in cleanups) {
            val failure = runCatching { cleanup.run() }.exceptionOrNull() ?: continue
            val previous = primary
            if (previous == null) primary = failure else FabricMinecraftFailures.addSuppressed(previous, failure)
        }
        if (primary != null) throw primary
    }

    /**
     * Drops the GUI queue before attempting every independently owned CPU mesh close, without closing GPU buffers.
     * Previously processed records may appear after partial failure; their native close operations are idempotent.
     */
    @JvmStatic
    @Throws(Throwable::class)
    public fun closeMeshes(meshes: MutableList<out AutoCloseable>) {
        val pending = meshes.toList()
        meshes.clear()
        run(cleanups = Array(pending.size) { index -> Cleanup { pending[index].close() } })
    }

    /**
     * Borrows one synchronous owner-thread cleanup without transferring native resource ownership.
     */
    @InternalStrataRuntimeApi
    public fun interface Cleanup {
        /**
         * Attempts the borrowed cleanup and propagates its original failure unchanged.
         */
        @Throws(Throwable::class)
        public fun run()
    }
}
