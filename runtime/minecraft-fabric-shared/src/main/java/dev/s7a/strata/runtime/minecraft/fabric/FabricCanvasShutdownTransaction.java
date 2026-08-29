package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;

/**
 * Orders native Canvas shutdown before the platform destroys its rendering context.
 *
 * <p>The caller supplies owner-thread operations borrowed only for this synchronous call.
 * Screen cleanup failures do not skip independent queue cleanup or vanilla shutdown.
 * Device resources are released only after queue discard succeeds, and an original vanilla failure remains primary.</p>
 */
@InternalStrataRuntimeApi
public final class FabricCanvasShutdownTransaction {
    private FabricCanvasShutdownTransaction() {
    }

    /**
     * Stops callbacks, discards unconsumed GUI work, releases the device, and always attempts vanilla shutdown.
     *
     * @param stopScreen terminal screen cleanup, including input cancellation and source detachment.
     * @param discardGui queue-discard operation that must complete before native resource release.
     * @param releaseDevice terminal native completion and resource cleanup.
     * @param closeVanilla original platform shutdown, always attempted.
     * @throws Throwable preserving an original platform failure, or the first cleanup failure when the platform succeeds.
     */
    public static void run(Runnable stopScreen, Runnable discardGui, Runnable releaseDevice, Runnable closeVanilla) throws Throwable {
        Throwable cleanup = attempt(null, stopScreen);
        boolean discarded = false;
        try {
            discardGui.run();
            discarded = true;
        } catch (Throwable failure) {
            cleanup = add(cleanup, failure);
        }
        if (discarded) cleanup = attempt(cleanup, releaseDevice);
        try {
            closeVanilla.run();
        } catch (Throwable failure) {
            if (cleanup != null) FabricMinecraftFailures.addSuppressed(failure, cleanup);
            throw failure;
        }
        if (cleanup != null) throw cleanup;
    }

    private static Throwable attempt(Throwable primary, Runnable action) {
        try {
            action.run();
            return primary;
        } catch (Throwable failure) {
            return add(primary, failure);
        }
    }

    private static Throwable add(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        FabricMinecraftFailures.addSuppressed(primary, secondary);
        return primary;
    }
}
