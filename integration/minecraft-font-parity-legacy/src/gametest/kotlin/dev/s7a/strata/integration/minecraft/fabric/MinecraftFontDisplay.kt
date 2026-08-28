package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Configures the loaded legacy client's native framebuffer for exact density comparisons.
 * Minecraft and GLFW state is borrowed only on the client thread; no native handle or mutable state escapes a call.
 */
internal object MinecraftFontDisplay {
    private const val SCALE_TIMEOUT_TICKS = 1_200

    /**
     * Selects [scale] and waits for the exact [size] framebuffer before returning.
     *
     * Minecraft's legacy window transition uses the monitor-switching GLFW operation even when the window is already windowed.
     * The explicit GLFW window-size operation follows that transition so the window manager receives a dedicated windowed resize request while the original call keeps Minecraft's stored dimensions coherent.
     * The framebuffer must converge before applying [scale] because legacy Minecraft clamps that value against its currently cached framebuffer dimensions.
     *
     * @param context loaded-client coordinator owning client-thread and tick handoffs.
     * @param scale exact integral GUI scale required by the comparison.
     * @param size exact physical framebuffer dimensions required by the comparison.
     * @throws IllegalStateException when the framebuffer, window mode, or requested and effective GUI scale does not converge before the bounded timeout.
     * Client-thread, tick, and pointer coordination failures from [context] propagate unchanged.
     */
    fun configure(
        context: MinecraftLoadedTestContext,
        scale: Int,
        size: IntSize,
    ) {
        context.computeOnClient { minecraft ->
            minecraft.window.setWindowed(size.width, size.height)
            GLFW.glfwSetWindowSize(minecraft.window.window, size.width, size.height)
        }
        var state = context.computeOnClient(::snapshot)
        var elapsedTicks = 0
        while (state.matchesFramebuffer(size).not() && elapsedTicks < SCALE_TIMEOUT_TICKS) {
            context.waitTicks(1)
            state = context.computeOnClient(::snapshot)
            elapsedTicks += 1
        }
        check(state.matchesFramebuffer(size)) {
            "Loaded framebuffer did not converge: expectedFramebuffer=${size.width}x${size.height}, $state"
        }
        context.computeOnClient { minecraft ->
            minecraft.options.forceUnicodeFont().set(false)
            minecraft.options.guiScale().set(scale)
            minecraft.resizeDisplay()
        }
        state = context.computeOnClient(::snapshot)
        while (state.matches(size, scale).not() && elapsedTicks < SCALE_TIMEOUT_TICKS) {
            context.waitTicks(1)
            state = context.computeOnClient(::snapshot)
            elapsedTicks += 1
        }
        check(state.matches(size, scale)) {
            "Loaded display did not converge: expectedFramebuffer=${size.width}x${size.height}, expectedGuiScale=$scale, $state"
        }
        context.movePointer(IntOffset.Zero)
        context.waitTicks(3)
    }

    private fun snapshot(minecraft: Minecraft): State {
        val nativeWindowWidth = IntArray(1)
        val nativeWindowHeight = IntArray(1)
        val nativeFramebufferWidth = IntArray(1)
        val nativeFramebufferHeight = IntArray(1)
        val handle = minecraft.window.window
        GLFW.glfwGetWindowSize(handle, nativeWindowWidth, nativeWindowHeight)
        GLFW.glfwGetFramebufferSize(handle, nativeFramebufferWidth, nativeFramebufferHeight)
        return State(
            cachedFramebuffer = IntSize(minecraft.window.width, minecraft.window.height),
            nativeFramebuffer = IntSize(nativeFramebufferWidth.single(), nativeFramebufferHeight.single()),
            nativeWindow = IntSize(nativeWindowWidth.single(), nativeWindowHeight.single()),
            requestedGuiScale = minecraft.options.guiScale().get(),
            guiScale = minecraft.window.guiScale,
            cachedFullscreen = minecraft.window.isFullscreen,
            nativeWindowed = GLFW.glfwGetWindowMonitor(handle) == 0L,
        )
    }

    private data class State(
        val cachedFramebuffer: IntSize,
        val nativeFramebuffer: IntSize,
        val nativeWindow: IntSize,
        val requestedGuiScale: Int,
        val guiScale: Double,
        val cachedFullscreen: Boolean,
        val nativeWindowed: Boolean,
    ) {
        fun matches(
            expectedFramebuffer: IntSize,
            expectedGuiScale: Int,
        ): Boolean =
            matchesFramebuffer(expectedFramebuffer) && requestedGuiScale == expectedGuiScale &&
                guiScale == expectedGuiScale.toDouble()

        fun matchesFramebuffer(expectedFramebuffer: IntSize): Boolean =
            cachedFramebuffer == expectedFramebuffer && nativeFramebuffer == expectedFramebuffer &&
                cachedFullscreen.not() && nativeWindowed

        override fun toString(): String =
            "cachedFramebuffer=${cachedFramebuffer.width}x${cachedFramebuffer.height}, " +
                "nativeFramebuffer=${nativeFramebuffer.width}x${nativeFramebuffer.height}, " +
                "nativeWindow=${nativeWindow.width}x${nativeWindow.height}, requestedGuiScale=$requestedGuiScale, " +
                "guiScale=$guiScale, " +
                "cachedFullscreen=$cachedFullscreen, nativeWindowed=$nativeWindowed"
    }
}
