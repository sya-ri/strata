package dev.s7a.strata.integration.minecraft.fabric;

import com.mojang.blaze3d.platform.Window;
import dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess;
import dev.s7a.strata.spi.InternalStrataRuntimeApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Permits one explicit native focus callback through Fabric's client-test input cancellation.
 *
 * <p>The borrowed active window and allowance belong to the client thread for the synchronous invocation only.
 * The allowance matches the actual native callback id and is consumed before vanilla or Strata callbacks run.
 * Other callbacks, windows, threads, and unscoped operating-system events keep the harness cancellation.
 * Standalone runners without Fabric's client-test module invoke the native callback without changing its behavior.</p>
 */
@InternalStrataRuntimeApi
public final class MinecraftCanvasWindowTestScope {
    private static final ThreadLocal<Invocation> ACTIVE = new ThreadLocal<>();

    private MinecraftCanvasWindowTestScope() {
    }

    /**
     * Invokes the borrowed window's real focus callback while allowing exactly one matching harness cancellation through.
     *
     * <p>Callers supply the actual handle on the client thread and restore native focus after their test.
     * An unscoped callback first verifies that the harness still cancels ordinary events.
     * Nested invocations are rejected; the allowance is removed before any callback failure escapes.
     * A missing expected cancellation guard fails explicitly, preserving a callback failure as primary.</p>
     *
     * @param window active client-owned window, never retained after this call
     * @param handle handle belonging to that window
     * @param focused requested native focus state
     * @throws Throwable the original callback failure, or a failed thread, ownership, or harness assertion
     */
    public static void invoke(Window window, long handle, boolean focused) throws Throwable {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread() == false || client.getWindow() != window) {
            throw new IllegalStateException("Native focus acceptance requires the active window on its client thread.");
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Native focus acceptance cannot nest its one-callback allowance.");
        }
        MinecraftCanvasWindowTestAccess access = (MinecraftCanvasWindowTestAccess) (Object) window;
        if (hasHarnessCancellation() == false) {
            access.strataCanvasFocus(handle, focused);
            return;
        }

        boolean before = client.isWindowActive();
        access.strataCanvasFocus(handle, before == false);
        if (client.isWindowActive() != before) {
            throw new IllegalStateException("Fabric must still cancel native focus events outside the explicit test scope.");
        }

        Invocation invocation = new Invocation(window);
        ACTIVE.set(invocation);
        Throwable failure = null;
        try {
            access.strataCanvasFocus(handle, focused);
        } catch (Throwable exception) {
            failure = exception;
            throw exception;
        } finally {
            ACTIVE.remove();
            if (invocation.accepted == false) {
                IllegalStateException mismatch = new IllegalStateException("The expected Fabric focus cancellation guard did not execute.");
                if (failure == null) {
                    throw mismatch;
                }
                failure.addSuppressed(mismatch);
            }
        }
    }

    /**
     * Preserves the verified Fabric cancellation except for one matching explicit focus invocation.
     *
     * <p>This public test bridge is called from the instrumented native window, with the callback id derived from
     * its remapped invoker by the test config plugin. Other callback ids and threads are never allowed through.
     * No arguments are retained here, and invalid callback state fails before any vanilla or producer work.</p>
     *
     * @param window receiver of Fabric's borrowed cancellation callback
     * @param callback original cancellable native callback information
     * @param focusCallbackId actual mapped or unmapped native focus method name
     */
    public static void cancelUnlessNativeFocus(Object window, CallbackInfo callback, String focusCallbackId) {
        Invocation invocation = ACTIVE.get();
        if (invocation == null || invocation.window != window || invocation.accepted || focusCallbackId.equals(callback.getId()) == false) {
            callback.cancel();
            return;
        }
        if (callback.isCancellable() == false || callback.isCancelled()) {
            throw new IllegalStateException("Fabric's native focus cancellation state no longer matches the verified test boundary.");
        }
        invocation.accepted = true;
    }

    /**
     * Reports whether the installed Fabric client-test module activates its input cancellation in this process.
     *
     * <p>This read-only bootstrap query uses the upstream module id and property-presence contract, not a game version.
     * It does not acquire native resources or change the harness configuration.</p>
     */
    public static boolean hasHarnessCancellation() {
        return FabricLoader.getInstance().isModLoaded("fabric-client-gametest-api-v1")
            && System.getProperty("fabric.client.gametest") != null;
    }

    private static final class Invocation {
        private final Object window;
        private boolean accepted;

        private Invocation(Object window) {
            this.window = window;
        }
    }
}
