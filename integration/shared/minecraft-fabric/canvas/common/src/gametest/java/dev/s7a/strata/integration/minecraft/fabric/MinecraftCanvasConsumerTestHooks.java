package dev.s7a.strata.integration.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;

/**
 * Owns a single test-only callback pair at the real native GUI consumer boundary.
 *
 * <p>Every call belongs to the client render thread. Callbacks are removed before invocation,
 * so reentrant consumers and throwing callbacks cannot repeat them or retain a closed test screen.
 * This class exists only in the loaded-test artifact and is never published by a runtime module.</p>
 */
@InternalStrataRuntimeApi
public final class MinecraftCanvasConsumerTestHooks {
    private static Runnable before;
    private static Runnable after;

    private MinecraftCanvasConsumerTestHooks() {}

    /**
     * Arms the next real GUI consumer after a Canvas renderer has recorded its capture.
     *
     * @param beforeConsumer callback closing the test screen before the consumer reads its queued native target.
     * @param afterConsumer callback observing a successful consumer return; ownership transfers until invocation or reset.
     * @throws IllegalStateException when another one-shot test pair is still armed.
     */
    public static void arm(Runnable beforeConsumer, Runnable afterConsumer) {
        if (before != null || after != null) {
            throw new IllegalStateException("A Canvas GUI-consumer test callback is already armed.");
        }
        before = beforeConsumer;
        after = afterConsumer;
    }

    /**
     * Takes and invokes the before callback, returning its paired successful-consumption callback.
     *
     * @return a borrowed one-shot callback for the current native wrapper, or null when this consumer is unrelated.
     * @throws RuntimeException when the test's before-consumption assertion fails; callbacks have already been removed.
     */
    public static Runnable beforeConsumer() {
        Runnable currentBefore = before;
        Runnable currentAfter = after;
        before = null;
        after = null;
        if (currentBefore == null) {
            return null;
        }
        currentBefore.run();
        return currentAfter;
    }

    /**
     * Clears any unconsumed callback references during test cleanup without invoking them or changing native ownership.
     */
    public static void reset() {
        before = null;
        after = null;
    }
}
