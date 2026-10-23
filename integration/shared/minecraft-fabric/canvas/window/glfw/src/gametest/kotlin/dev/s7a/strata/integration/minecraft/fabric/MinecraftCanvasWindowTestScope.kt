package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.Window
import dev.s7a.strata.integration.minecraft.fabric.mixin.canvas.MinecraftCanvasWindowTestAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Permits exactly one matching native focus callback through Fabric's loaded-test input cancellation.
 * The active window and allowance belong to the client thread for this synchronous invocation only.
 * Other callbacks, windows, threads and unscoped operating-system events retain harness cancellation.
 */
@InternalStrataRuntimeApi
public object MinecraftCanvasWindowTestScope {
    private val active = ThreadLocal<Invocation>()

    /**
     * Invokes real native focus after checking client ownership and unscoped cancellation.
     * Rejects nesting and removes the allowance before propagating the exact callback failure.
     * A missing expected harness guard fails explicitly, suppressed behind any callback failure.
     */
    @JvmStatic
    @Throws(Throwable::class)
    public fun invoke(
        window: Window,
        handle: Long,
        focused: Boolean,
    ) {
        val client = Minecraft.getInstance()
        check(client.isSameThread && client.window === window) {
            "Native focus acceptance requires the active window on its client thread."
        }
        check(active.get() == null) { "Native focus acceptance cannot nest its one-callback allowance." }
        val access = MinecraftCanvasWindowTestAccess::class.java.cast(window)
        if (hasHarnessCancellation().not()) {
            access.strataCanvasFocus(handle, focused)
            return
        }
        val before = client.isWindowActive
        access.strataCanvasFocus(handle, before.not())
        check(client.isWindowActive == before) { "Fabric must still cancel native focus events outside the explicit test scope." }
        val invocation = Invocation(window)
        active.set(invocation)
        val result =
            try {
                runCatching { access.strataCanvasFocus(handle, focused) }
            } finally {
                active.remove()
            }
        if (invocation.accepted.not()) {
            val mismatch = IllegalStateException("The expected Fabric focus cancellation guard did not execute.")
            val failure = result.exceptionOrNull() ?: throw mismatch
            failure.addSuppressed(mismatch)
        }
        result.getOrThrow()
    }

    /**
     * Called from the verified instrumented window with its actual mapped focus callback id.
     * Consumes one matching allowance before vanilla work; all other callbacks remain cancelled.
     */
    @JvmStatic
    public fun cancelUnlessNativeFocus(
        window: Any,
        callback: CallbackInfo,
        focusCallbackId: String,
    ) {
        val invocation = active.get()?.takeIf { it.window === window }
        if (invocation == null || invocation.accepted || focusCallbackId != callback.id) {
            callback.cancel()
            return
        }
        check(callback.isCancellable && callback.isCancelled.not()) {
            "Fabric's native focus cancellation state no longer matches the verified test boundary."
        }
        invocation.accepted = true
    }

    /**
     * Observes the installed test harness's module and property-presence contract without changing it.
     */
    public fun hasHarnessCancellation(): Boolean = FabricLoader.getInstance().isModLoaded("fabric-client-gametest-api-v1") && System.getProperty("fabric.client.gametest") != null

    /**
     * Owns one borrowed window identity and its consumed allowance for the active synchronous call.
     */
    private class Invocation(
        val window: Any,
        var accepted: Boolean = false,
    )
}
