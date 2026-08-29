package dev.s7a.strata.runtime.minecraft.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices;
import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Version-owned native consumption and device-lifetime bridge.
 *
 * <p>Every call runs on the client/render thread. This helper owns no screen, command list, or resource.
 * The wrapped native operation remains primary if independent canvas cleanup also fails.</p>
 */
@InternalStrataRuntimeApi
public final class FabricMinecraftCanvasHooks {
    private static boolean shutdownStarted;

    private FabricMinecraftCanvasHooks() {
    }

    /**
     * Permanently prevents new Strata screen attachments before any terminal screen cleanup callback runs.
     *
     * <p>The render-thread flag is idempotent, retains no screen, and stays terminal even when later cleanup fails.</p>
     *
     * @throws IllegalStateException when called off the render thread.
     */
    public static void beginShutdown() {
        RenderSystem.assertOnRenderThread();
        shutdownStarted = true;
    }

    /**
     * Rejects reentrant new screen attachment after terminal client shutdown has begun.
     *
     * <p>Call on the render thread before attaching retained input or source subscriptions.</p>
     *
     * @throws IllegalStateException when called off the render thread or after terminal shutdown begins.
     */
    public static void requireRunning() {
        RenderSystem.assertOnRenderThread();
        if (shutdownStarted) throw new IllegalStateException("Strata screens cannot attach during native client shutdown.");
    }

    /**
     * Records consumption only after native GUI work has actually been submitted.
     *
     * @param primary original GUI failure, or null after successful consumption.
     * @throws Throwable when cleanup fails without an earlier primary failure.
     */
    public static void afterGui(Throwable primary) throws Throwable {
        if (primary != null) {
            afterFailedFrame(primary);
            return;
        }
        try {
            NativeCanvasDevices.INSTANCE.consumed();
        } catch (Throwable failure) {
            afterFailedFrame(failure);
            throw failure;
        }
    }

    /**
     * Polls completion independently of the active screen without fencing extraction or blocking.
     *
     * @param primary original frame failure, or null after success.
     * @throws Throwable when cleanup fails without an earlier primary failure.
     */
    public static void afterFrame(Throwable primary) throws Throwable {
        if (primary != null) {
            afterFailedFrame(primary);
            return;
        }
        try {
            NativeCanvasDevices.INSTANCE.poll();
        } catch (Throwable failure) {
            afterFailedFrame(failure);
            throw failure;
        }
    }

    /**
     * Stops the current Strata screen before native device or window teardown without retaining it.
     *
     * @param client native client whose active screen is borrowed for terminal cleanup.
     * @throws RuntimeException when retained input or source cleanup fails, after the host has attempted independent release.
     */
    public static void closeActiveScreen(Minecraft client) {
        Screen screen = FabricMinecraftScreenAccess.currentScreen(client);
        if (screen instanceof FabricMinecraftInputReset owner) owner.close();
    }

    /**
     * Cancels retained input after the native window has committed loss of focus.
     *
     * @param client native client whose active screen is borrowed only for the owner-thread reset.
     * @throws RuntimeException when input cancellation fails after independent retained cleanup has been attempted.
     */
    public static void resetActiveInput(Minecraft client) {
        if (shutdownStarted) return;
        Screen screen = FabricMinecraftScreenAccess.currentScreen(client);
        if (screen instanceof FabricMinecraftInputReset owner) owner.resetInputFromNative();
    }

    private static void afterFailedFrame(Throwable primary) {
        Minecraft client = Minecraft.getInstance();
        suppress(primary, () -> closeActiveScreen(client));
        suppress(primary, () -> FabricMinecraftCanvasGuiConsumption.discardCanvasGui(client));
        suppress(primary, () -> NativeCanvasDevices.INSTANCE.failedGui());
        suppress(primary, () -> NativeCanvasDevices.INSTANCE.poll());
    }

    private static void suppress(Throwable primary, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable failure) {
            FabricMinecraftFailures.addSuppressed(primary, failure);
        }
    }
}
