package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCloseReason
import net.minecraft.client.Minecraft

/**
 * Connects native GUI consumption and device lifetime on the client/render thread without owning screen or resource references.
 * A native operation's original failure remains primary when independent Canvas cleanup also fails.
 */
@InternalStrataRuntimeApi
public object FabricMinecraftCanvasHooks {
    private var shutdownStarted = false

    /**
     * Permanently prevents new attachments before closing remote and shared sessions, including when later cleanup fails.
     */
    @JvmStatic
    public fun beginShutdown() {
        RenderSystem.assertOnRenderThread()
        shutdownStarted = true
        FabricRemoteScreens.shutdown()
        FabricUiSessions.closeAll(UiCloseReason.Disconnected)
    }

    /**
     * Rejects off-render-thread or terminal reentrant attachment before retained input and sources are attached.
     */
    @JvmStatic
    public fun requireRunning() {
        RenderSystem.assertOnRenderThread()
        check(shutdownStarted.not()) { "Strata screens cannot attach during native client shutdown." }
    }

    /**
     * Acknowledges native GUI consumption and attempts independent cleanup after an original or acknowledgement failure.
     */
    @JvmStatic
    @Throws(Throwable::class)
    public fun afterGui(primary: Throwable?) {
        afterNativeWork(primary, NativeCanvasDevices::consumed)
    }

    /**
     * Polls native completion independently of the active screen without fencing extraction or blocking.
     */
    @JvmStatic
    @Throws(Throwable::class)
    public fun afterFrame(primary: Throwable?) {
        afterNativeWork(primary, NativeCanvasDevices::poll)
    }

    /**
     * Borrows and terminally closes the active Strata screen before native context or window teardown.
     */
    @JvmStatic
    public fun closeActiveScreen(client: Minecraft) {
        (FabricMinecraftScreenAccess.currentScreen(client) as? FabricMinecraftScreen)?.close()
    }

    /**
     * Cancels retained input after committed native focus loss, except after terminal shutdown begins.
     */
    @JvmStatic
    public fun resetActiveInput(client: Minecraft) {
        if (shutdownStarted) return
        FabricUiSessions.resetInput()
        (FabricMinecraftScreenAccess.currentScreen(client) as? FabricMinecraftScreen)?.resetInputFromNative()
    }

    private fun afterNativeWork(
        primary: Throwable?,
        operation: () -> Unit,
    ) {
        if (primary != null) {
            afterFailedFrame(primary)
            return
        }
        val failure = runCatching(operation).exceptionOrNull() ?: return
        afterFailedFrame(failure)
        throw failure
    }

    private fun afterFailedFrame(primary: Throwable) {
        val client = Minecraft.getInstance()
        suppress(primary) { closeActiveScreen(client) }
        suppress(primary) { discardCanvasGui(client) }
        suppress(primary) { NativeCanvasDevices.failedGui() }
        suppress(primary) { NativeCanvasDevices.poll() }
    }

    private fun suppress(
        primary: Throwable,
        cleanup: () -> Unit,
    ) {
        val failure = runCatching(cleanup).exceptionOrNull() ?: return
        FabricMinecraftFailures.addSuppressed(primary, failure)
    }
}
