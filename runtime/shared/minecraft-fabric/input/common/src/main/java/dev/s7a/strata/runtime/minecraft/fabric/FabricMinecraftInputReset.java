package dev.s7a.strata.runtime.minecraft.fabric;

/**
 * Internal native-window bridge to retained input cleanup.
 *
 * <p>The active screen implements this capability without introducing a second input-event hierarchy.
 * The window callback borrows the screen only on the client thread; failures preserve normal session cleanup semantics.</p>
 */
interface FabricMinecraftInputReset extends AutoCloseable {
    /**
     * Cancels captured pointer ownership and resets hover/focus on the client thread.
     *
     * @throws RuntimeException when a user callback or cleanup fails.
     */
    void resetInputFromNative();

    /**
     * Stops input and source callbacks during native consumer failure or device shutdown without navigating to another screen.
     *
     * <p>The owner-thread screen closes its retained host exactly once; in-flight GPU resources remain device-owned.</p>
     *
     * @throws RuntimeException when terminal cleanup fails, after independent cleanup has been attempted.
     */
    @Override
    void close();
}
