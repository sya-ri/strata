package dev.s7a.strata.integration.minecraft.fabric;

import dev.s7a.strata.spi.InternalStrataRuntimeApi;

/**
 * Owns one explicit loaded-test capture callback after the current Canvas GUI submission has been consumed.
 *
 * <p>All calls belong to the client render thread. The callback is removed before invocation and never runs from extraction.
 * The immediate family invokes this after Screen.renderWithTooltip returns, following the Canvas screen's final flush.
 * Deferred families invoke it only after the actual GuiRenderer consumer returns. No runtime artifact includes this class.</p>
 */
@InternalStrataRuntimeApi
public final class MinecraftCanvasCaptureTestHooks {
    private static Runnable callback;

    private MinecraftCanvasCaptureTestHooks() {}

    /**
     * Transfers a one-shot callback that captures both the just-presented immutable receipt and the native framebuffer.
     *
     * @param afterConsumer owner-thread callback whose references are released before invocation or reset.
     * @throws IllegalStateException when another explicit capture is already armed.
     */
    public static void arm(Runnable afterConsumer) {
        if (callback != null) {
            throw new IllegalStateException("A Canvas native-generation capture callback is already armed.");
        }
        callback = afterConsumer;
    }

    /**
     * Takes and runs the callback only after actual GUI consumption, without retaining it through user code.
     *
     * @throws RuntimeException preserving a capture or assertion failure after the callback has already been cleared.
     */
    public static void afterConsumer() {
        Runnable current = callback;
        callback = null;
        if (current != null) {
            current.run();
        }
    }

    /**
     * Releases an unconsumed callback on the client thread without invoking it or modifying native resource ownership.
     */
    public static void reset() {
        callback = null;
    }
}
